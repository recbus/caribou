(ns io.recbus.caribou
  "Transactionally install migrations with validation of local migrations
  versus previously transacted migrations.  There is sufficient tolerance
  in the definition of migration dependencies to allow concurrent development
  of unrelated migrations+dependent code on a shared database while enforcing
  installation ordering for dependent migrations."
  (:require [clojure.java.io :as io]
            [clojure.set]
            [datomic.client.api :as d]
            [io.recbus.caribou.acyclic-digraph :as ad]
            [io.recbus.caribou.datomic-helpers :as helpers]))

(def ^:dynamic *epoch* 0)

(defn- synthesize-root
  "Add a single root node to the graph that depends on all roots in the graph."
  [mgraph]
  (assoc mgraph ::root (with-meta (ad/roots mgraph) {:basis nil})))

(defn- ->mgraph
  "A reducing function suitable for converting a migrations map into a canonical
  acyclic digraph map whose edges represent dependency order.  The metadata on
  the map values holds the payload."
  ([] {})
  ([graph] (synthesize-root graph))
  ([graph [k v]] (assoc graph k (with-meta (set (:dependencies v))
                                  (dissoc v :dependencies)))))

(defn- mghash
  "Compute the hash of the migration graph that represents both the acyclic digraph structure
  and payload of each vertex (the migration transaction)."
  [edges]
  (-> ((fn mghash* [vertex cache]
         (assert (cache vertex) (format "The migration %s is listed as a dependency but is not present." vertex))
         (if-let [h (-> cache vertex meta ::hash)]
           [h cache]
           (let [[h cache] (loop [h 0 n 0 [child & children] (edges vertex) cache cache]
                             (if child
                               (let [[hc cache] (mghash* child cache)]
                                 (recur (unchecked-add-int h hc) (inc n) children cache))
                               ;; The un-ordered collection hash alogrithm; see
                               ;; https://clojure.org/reference/data_structures
                               [(mix-collection-hash h n) cache]))
                 ;; this is an ordered collection hash of this vertex's basis then the
                 ;; unordered collection of its children.
                 h (hash [(-> cache vertex meta :basis) h])]
             [h (update cache vertex vary-meta assoc ::hash h)]))) ::root edges)
      second))

(defn- topological-sort
  [mgraph]
  (let [tsort (ad/topological-sort mgraph)
        c (fn topological-order [x y] (compare (.indexOf tsort x) (.indexOf tsort y)))]
    (into (sorted-map-by c) mgraph)))

(defn- install-schema
  [conn]
  (d/transact conn {:tx-data [{:db/ident ::name
                               :db/doc "The name of this migration"
                               :db/valueType :db.type/keyword
                               :db/cardinality :db.cardinality/one}
                              {:db/ident ::epoch
                               :db/doc "The epoch of this migration"
                               :db/valueType :db.type/long
                               :db/cardinality :db.cardinality/one}
                              {:db/ident ::hash
                               :db/doc "The hash of the migration (sub-) tree rooted here"
                               :db/valueType :db.type/long
                               :db/cardinality :db.cardinality/one}
                              {:db/ident ::name+epoch
                               :db/doc "The tuple of the name and the epoch of this migration"
                               :db/valueType :db.type/tuple
                               :db/tupleAttrs [::name ::epoch]
                               :db/cardinality :db.cardinality/one
                               :db/unique :db.unique/identity}
                              {:db/ident ::hash+epoch
                               :db/doc "The tuple of the hash and the epoch of this migration"
                               :db/valueType :db.type/tuple
                               :db/tupleAttrs [::hash ::epoch]
                               :db/cardinality :db.cardinality/one
                               :db/unique :db.unique/value}
                              {:db/ident ::dependencies
                               :db/doc "The dependencies of this migration"
                               :db/valueType :db.type/ref
                               :db/cardinality :db.cardinality/many}
                              {:db/ident ::effector
                               :db/doc "The name of the migration whose step-fn effected this transaction"
                               :db/valueType :db.type/keyword
                               :db/cardinality :db.cardinality/one}]}))

(defn install-root
  [conn]
  (let [h (-> (transduce identity ->mgraph {}) mghash ::root meta ::hash)]
    (d/transact conn {:tx-data [{:db/id "root"
                                 ::name ::root
                                 ::epoch *epoch*
                                 ::dependencies #{}}
                                [:db/cas "root" ::hash nil h]]})))

(defn history
  "Fetch the complete history of migrations from the given database."
  ;; TODO: fetch only the first two layers (root + its direct dependencies)
  ;; and use that data to validate local migration source (using the structural
  ;; hash values) and to extract the outstanding migrations.  There is no strict
  ;; requirement to download the entire history to perform those tasks.
  [db]
  (let [rules '[[(walk ?from ?attr ?to)
                 [?from ?attr ?to]]
                [(walk ?from ?attr ?to)
                 [?from ?attr ?intermediate]
                 (walk ?intermediate ?attr ?to)]

                [(migration ?root ?meid)
                 [(identity ?root) ?meid]]
                [(migration ?root ?meid)
                 (walk ?root ::dependencies ?meid)]]
        ms (d/q {:query '[:find (pull ?meid [:db/id ::hash ::name {::dependencies [::name]}])
                          :in $ % ?epoch
                          :where
                          , [?root ::name ::root]
                          , [?root ::epoch ?epoch]
                          , (migration ?root ?meid)]
                 :args [db rules *epoch*]})
        xform (comp (map first)
                    (map (fn [{::keys [hash name dependencies] eid :db/id}]
                           (let [dependencies (map ::name dependencies)]
                             [name (with-meta (set dependencies) {:db/id eid ::hash hash})]))))]
    (into {} xform ms)))

(defn- history!
  [conn]
  (let [db (d/db conn)]
    (try (let [h (history db)]
           (if (empty? h)
             (let [{db :db-after} (install-root conn)]
               (history db))
             h))
         (catch clojure.lang.ExceptionInfo e
           (let [{error :db/error :keys [e a v v-old]
                  cancelled? :datomic/cancelled
                  category :cognitect.anomalies/category} (ex-data e)]
             (case [category error]
               [:cognitect.anomalies/incorrect :db.error/not-an-entity] (do (try (install-schema conn)
                                                                                 (catch Exception _))
                                                                            (history! conn))
               [:cognitect.anomalies/conflict :db.error/cas-failed] (history! conn)
               (throw e)))))))

(defn- transact
  [conn r0 r1 {:keys [name hash dependencies tx-data]}]
  (let [root-eid (-> r0 meta :db/id)
        subsumed (map (fn [sd] [:db/retract root-eid ::dependencies [::name+epoch [sd *epoch*]]]) (clojure.set/difference r0 r1))
        tx-data (concat tx-data [{:db/id root-eid ::name ::root ::epoch *epoch* ::dependencies #{"migration"}}
                                 [:db/cas root-eid ::hash (-> r0 meta ::hash) (-> r1 meta ::hash)]
                                 {:db/id "migration"
                                  ::name name
                                  ::hash hash
                                  ::epoch *epoch*
                                  ::dependencies (map (fn [m] [::name+epoch [m *epoch*]]) dependencies)}]
                        subsumed)]
    (try (let [{:keys [tx-data] :as result} (d/transact conn {:tx-data tx-data})]
           result)
         (catch clojure.lang.ExceptionInfo ex
           (let [{error :db/error :keys [e a v v-old]
                  cancelled? :datomic/cancelled
                  category :cognitect.anomalies/category} (ex-data ex)]
             (if (= [:cognitect.anomalies/conflict :db.error/cas-failed true root-eid ::hash]
                    [category error cancelled? e a])
               nil ;; "Conflict transacting migration, continuing."
               (throw (ex-info "Failed to transact migration" {::name name ::root-transition (map (comp ::hash meta) [r0 r1])} ex)))))
         (catch Exception ex
           (throw (ex-info "Failed to transact migration" {::name name ::root-transition (map (comp ::hash meta) [r0 r1])} ex))))))

(defn- merge-graphs
  [graphL graphR]
  (let [h (comp ::hash meta)
        validating-meta-merge (fn [k l r]
                                (assert (= (h l) (h r))
                                        (format "Local and remote data for migration %s does not match: %s %s!"
                                                k (h l) (h r)))
                                (vary-meta l merge (meta r)))
        merge-entry (fn [m [k v]]
                      (if (contains? m k)
                        (assoc m k (validating-meta-merge k (get m k) v))
                        (assoc m k v)))
        [onlyL common onlyR] (ad/gdiff (dissoc graphL ::root) (dissoc graphR ::root))]
    (-> (reduce merge-entry (dissoc graphL ::root) (dissoc graphR ::root))
        synthesize-root
        mghash)))

(defn run-effect
  [conn effector step-fn context]
  (let [tx-metadata {:db/id "datomic.tx" ::effector effector}
        trxor (fn [{tx-data :tx-data :as context}]
                (when context
                  (-> context
                      (dissoc :tx-data)
                      ;; automatic tx on every iteration, perhaps degenerate w/ metadata.
                      (assoc :tx-result (d/transact conn {:tx-data (conj tx-data tx-metadata)})))))
        kf (fn [{{db :db-after} :tx-result :as context}]
             (cond-> (dissoc context :tx-result)
               db (assoc :db db)))]
    (transduce identity helpers/rf-txs (iteration (comp trxor step-fn)
                                                  :kf kf
                                                  :vf :tx-result
                                                  :initk (assoc context :db (d/db conn))))))

(defn- execute*
  [conn graphL]
  (loop [{r0 ::root :as graphR} (history! conn) out []]
    (let [mgraph (topological-sort (merge-graphs graphL graphR))
          [[k v :as m] & ms] (butlast (remove (comp :db/id meta val) mgraph))]
      (if m
        (let [{r1 ::root} (-> (reduce (fn [mgraph k] (dissoc mgraph k))
                                      (dissoc mgraph ::root)
                                      (reverse (keys ms)))
                              synthesize-root
                              mghash)
              {::keys [hash] :keys [tx-data step-fn context]} (meta v)
              db (or (when step-fn (:db-after (run-effect conn k step-fn context)))
                     (d/db conn))
              tx-order {:name k :hash hash :dependencies v :tx-data tx-data}]
          (if-let [{db :db-after :as result} (transact conn r0 r1 tx-order)]
            (recur (history db) (conj out result))
            (recur (history! conn) out)))
        out))))

(defn prepare
  "Prepare the `migrations` using the given `context` to augment the context passed to
  any migration transaction functions."
  [migrations context]
  (let [xform (comp (map (fn resolve-symbols [[k v]]
                           [k (update-vals v #(if (qualified-symbol? %)
                                                (requiring-resolve %)
                                                %))]))
                    (map (fn augment-context [[k v]]
                           [k (update v :context merge context)]))
                    (map (fn expand-tx [[k {:keys [tx-data tx-data-fn context] :as m}]]
                           [k (cond-> (dissoc m :tx-data-fn)
                                tx-data-fn (update :tx-data concat (tx-data-fn context)))]))
                    (map (fn identify-basis [[k {:keys [tx-data step-fn] :as v}]]
                           ;; identity is tx content + migration name (when unstable fns are present)
                           [k (assoc v :basis (cond-> {:tx-data tx-data}
                                                step-fn (assoc :step-fn k)))])))]
    (transduce xform ->mgraph migrations)))

(defn execute!
  "Execute the `migrations` against the Datomic connection `conn`, using the given `context` to
  augment the context passed to any migration transaction functions."
  ([conn migrations] (execute! conn migrations {}))
  ([conn migrations context]
   (let [results (->> (prepare migrations context)
                      mghash
                      (execute* conn))]
     (transduce identity helpers/rf-txs results))))

(defn analyze
  "Analyze the provided `migrations` in the given `context`."
  [migrations context]
  (let [{root ::root :as m} (->> (prepare migrations context)
                                 mghash)]
    [(-> root meta ::hash) m]))

(defn tx-report
  "Show transaction details of the applied migrations."
  [db]
  (let [txs (d/q {:query '[:find ?name ?tx ?txinstant ?hash
                           :in $ ?epoch
                           :where
                           , [?meid ::name ?name ?tx]
                           , [?meid ::hash ?hash ?tx] ; forcing the ?tx to match eliminates ::root
                           , [?meid ::epoch ?epoch]
                           , [?tx :db/txInstant ?txinstant]]
                  :args [db *epoch*]})
        ordering (into {} (map (fn [[n _ tx-instant & _]] [n tx-instant])) txs)
        c (fn [m0 m1] (compare [(ordering m0) m0] [(ordering m1) m1]))]
    (into (sorted-map-by c)
          (comp (map (fn [[nym tx txinstant h]] [nym {:id tx :txInstant txinstant :hash h}])))
          txs)))

(defn status
  "Report the migration status of the database `db`."
  [db]
  (let [h (history db)
        tree (d/pull db '[::name {::dependencies ...}] [::name+epoch [::root *epoch*]])]
    {::hash (-> h ::root meta ::hash)
     ::epoch *epoch*
     ::tx-report (tx-report db)
     ::dependency-tree tree}))

(defn assess
  "Assess the current migration state of the database `db` against the given `migrations`,
  evaluated in the given `context`.  Returns a vector of the local and remote database migration
  hashes and a map showing the only-local, common and only-remote (db) migrations."
  [db migrations context]
  (let [{rL ::root :as graphL} (->> (prepare migrations context)
                                    mghash)
        {rR ::root :as graphR} (history db)
        outstanding (butlast (remove (comp :db/id meta val) (topological-sort (merge-graphs graphL graphR))))
        [onlyL common onlyR] (ad/gdiff (dissoc graphL ::root) (dissoc graphR ::root))]
    [[(-> rL meta ::hash) (-> rR meta ::hash)]
     {:common-count (count common) :only-remote onlyR :only-local onlyL}]))
