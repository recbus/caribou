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

(defn install-tracking-entity
  [conn]
  (d/transact conn {:tx-data [{:db/ident ::name
                               :db/doc "The name of the this migration"
                               :db/valueType :db.type/keyword
                               :db/unique :db.unique/identity
                               :db/cardinality :db.cardinality/one}
                              {:db/ident ::hash
                               :db/doc "The hash of the migration (sub-) tree rooted here"
                               :db/valueType :db.type/long
                               :db/unique :db.unique/value
                               :db/cardinality :db.cardinality/one}
                              {:db/ident ::dependencies
                               :db/doc "The dependencies of this migration"
                               :db/valueType :db.type/ref
                               :db/cardinality :db.cardinality/many}
                              {:db/ident ::effector
                               :db/doc "The name of the migration that effected this transaction"
                               :db/valueType :db.type/keyword
                               :db/cardinality :db.cardinality/one}]})
  (let [h (-> {} ->mgraph mghash ::root meta ::hash)]
    (d/transact conn {:tx-data [{::name ::root
                                 ::hash h
                                 ::dependencies #{}}]})))

(defn history
  "Fetch the complete history of migrations from the given database."
  ;; TODO: fetch only the first two layers (root + its direct dependencies)
  ;; and use that data to validate local migration source (using the structural
  ;; hash values) and to extract the outstanding migrations.  There is no strict
  ;; requirement to download the entire history to perform those tasks.
  [db]
  (try (let [rules '[[(walk ?from ?attr ?to)
                      [?from ?attr ?to]]
                     [(walk ?from ?attr ?to)
                      [?from ?attr ?intermediate]
                      (walk ?intermediate ?attr ?to)]

                     [(migration ?root ?meid)
                      [(identity ?root) ?meid]]
                     [(migration ?root ?meid)
                      (walk ?root ::dependencies ?meid)]]
             ms (d/q {:query '[:find ?tx (pull ?meid [::hash ::name {::dependencies [::name]}])
                               :in $ %
                               :where
                               , [?root ::name ::root]
                               , (migration ?root ?meid)
                               , [?meid ::hash _ ?tx]]
                      :args [db rules]})
             tsort (map (comp ::name second) (sort-by first ms))
             xform (map (fn [[tx {::keys [hash name dependencies]}]]
                          (let [dependencies (map ::name dependencies)]
                            [name (with-meta (set dependencies) {:db/txid tx ::hash hash})])))]
         (into {} xform ms))
       (catch clojure.lang.ExceptionInfo e
         (when-not (= [:cognitect.anomalies/incorrect :db.error/not-an-entity]
                      ((juxt :cognitect.anomalies/category :db/error) (ex-data e)))
           (throw e)))))

(defn- history!
  [conn]
  (let [db (d/db conn)
        retry-limit 1]
    (if-let [h (history db)]
      h
      (loop [retries 5] ; brute force to get the tracking entity in place
        (let [result (try (install-tracking-entity conn)
                          (catch Exception e e))]
          (if (instance? Exception result)
            (if (<= retries retry-limit)
              (do (Thread/sleep (* retries (rand-int 10)))
                  (recur (inc retries)))
              (throw (ex-info "Unable to install schema tracking entity" {:retries retries} result)))
            (history! conn)))))))

(defn- transact
  [conn r0 r1 {:keys [name hash dependencies tx-data]}]
  (let [subsumed (map (fn [sd] [:db/retract [::name ::root] ::dependencies [::name sd]]) (clojure.set/difference r0 r1))
        tx-data (concat tx-data [{::name ::root ::dependencies #{"migration"}}
                                 [:db/cas [::name ::root] ::hash (-> r0 meta ::hash) (-> r1 meta ::hash)]
                                 {:db/id "migration"
                                  ::name name
                                  ::hash hash
                                  ::dependencies (map (fn [m] [::name m]) dependencies)}]
                        subsumed)]
    (try (let [{:keys [tx-data] :as result} (d/transact conn {:tx-data tx-data})]
           (tap> {:msg "Transacted migration" ::datom-count (count tx-data) ::name name ::root-transition (map (comp ::hash meta) [r0 r1])})
           result)
         (catch clojure.lang.ExceptionInfo ex
           (let [{error :db/error :keys [e a v v-old]
                  cancelled? :datomic/cancelled
                  category :cognitect.anomalies/category} (ex-data ex)]
             (if (= [:cognitect.anomalies/conflict true :db.error/cas-failed [::name ::root] ::hash]
                    [category cancelled? error e a])
               (do (tap> {:msg "Conflict transacting migration, continuing." ::name name})
                   nil)
               (do (tap> {:msg "Failed to transact migration" :ex ex ::name name ::root-transition (map (comp ::hash meta) [r0 r1])})
                   (throw ex)))))
         (catch Exception ex
           (do (tap> {:msg "Failed to transact migration" :ex ex ::name name ::root-transition (map (comp ::hash meta) [r0 r1])})
               (throw ex))))))

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
    (tap> {:msg "Migrations assessed." ::migrations {:common-count (count common) :only-remote onlyR :only-local onlyL}})
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
          [[k v :as m] & ms] (butlast (remove (comp :db/txid meta val) mgraph))]
      (if m
        (let [{r1 ::root} (-> (reduce (fn [mgraph k] (dissoc mgraph k))
                                      (dissoc mgraph ::root)
                                      (reverse (keys ms)))
                              synthesize-root
                              mghash)
              {::keys [hash] :keys [tx-data step-fn tx-fn context]} (meta v)
              db (or (when step-fn (:db-after (run-effect conn k step-fn context)))
                     (d/db conn))
              tx-data (cond-> tx-data tx-fn (concat (tx-fn (assoc context :db db))))
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
                    (map (fn identify-basis [[k {:keys [tx-data tx-fn step-fn] :as v}]]
                           ;; identity is tx content + migration name (when unstable fns are present)
                           [k (assoc v :basis (cond-> {:tx-data tx-data}
                                                tx-fn (assoc :tx-fn k)
                                                step-fn (assoc :step-fn k)))])))]
    (transduce xform ->mgraph migrations)))

(defn execute!
  "Execute the `migrations` against the Datomic connection `conn`, using the given `context` to
  augment the context passed to any migration transaction functions."
  [conn migrations context]
  (let [results (->> (prepare migrations context)
                     mghash
                     (execute* conn))]
    (transduce identity helpers/rf-txs results)))

(defn status
  [db]
  (let [hash (-> (d/pull db '[*] [::name ::root]) ::hash)]
    {::hash hash
     ::history (history db)
     ::tree (d/pull db '[::name {::dependencies ...}] [::name ::root])}))
