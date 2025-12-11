(ns io.recbus.caribou-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [io.recbus.caribou :as sut]))

(def ^:dynamic *connection*)

(defn runt-fn!
  "`runt!` helper function"
  [f]
  (let [once-fixture-fn (clojure.test/join-fixtures (:clojure.test/once-fixtures (meta *ns*)))
        each-fixture-fn (clojure.test/join-fixtures (:clojure.test/each-fixtures (meta *ns*)))]
    (once-fixture-fn
     (fn []
       (each-fixture-fn
        (fn []
          (f)))))))

(defmacro runt!
  "Run expression with fixtures"
  [& body]
  `(runt-fn! (fn [] ~@body)))

(defn db-setup
  [f]
  (let [system "caribou"
        db-name "caribou"
        client (d/client {:server-type :datomic-local
                          :storage-dir :mem
                          :system system})]
    (d/create-database client {:db-name db-name})
    (binding [*connection* (d/connect client {:db-name db-name})]
      (try (f)
           (finally
             (dl/release-db {:db-name db-name :system system}) ; this appears to work on non dev-local dbs as well
             (d/delete-database client {:db-name db-name}))))))

(use-fixtures :each db-setup)

(def reference-migrations
  (let [readers {'st/uri (fn [s] (java.net.URI. s))
                 'st/url (fn [s] (java.net.URL. s))}
        r (-> "migrations.edn" io/resource io/reader (java.io.PushbackReader.))]
    (edn/read {:readers readers} r)))

(deftest history-reports-migration-graph
  (let [migrations {::A {:tx-data [{:db/ident ::my-attr
                                    :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one}]
                         :dependencies []}}]
    (let [{db :db-after} (sut/migrate! *connection* migrations)]
      (is (= {::sut/root #{::A}
              ::A #{}}
             (sut/history db))))))

(deftest persisted-hash-is-stable
  (let [migrations {::A {:tx-data [{:db/ident ::my-attr
                                    :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one}]
                         :dependencies []}}]
    (let [{db :db-after} (sut/migrate! *connection* migrations)]
      (is (= -1096714776 (-> (sut/status db) ::sut/hash))))))

(deftest migrate-reference
  (let [{db :db-after} (sut/migrate! *connection* reference-migrations {})]
    (is (= -211898652 (-> (sut/status db) ::sut/hash)))))

(deftest migrate-reference-in-two-steps
  (let [m0 (select-keys reference-migrations [:st.schema/tx-metadata :st.schema/user :st.schema/generator])
        m1 reference-migrations
        {db :db-after} (sut/migrate! *connection* m0 {})
        {db :db-after} (sut/migrate! *connection* reference-migrations {})]
    (is (= -211898652 (-> (sut/status db) ::sut/hash)))))

(deftest migrations-are-independent-transactions
  (let [m0 {::A {:tx-data [{:db/ident ::my-attr
                            :db/valueType :db.type/string
                            :db/cardinality :db.cardinality/one}]
                 :dependencies []}
            ::B {:tx-data [{::nothing "a"}]
                 :dependencies [::A]}}
        e (try (sut/migrate! *connection* m0 {})
               (catch Exception e e))]
    (is (instance? Exception e))
    (let [status (sut/status (d/db *connection*))]
      (is (= -1096714776 (-> status ::sut/hash)) status))))

(deftest identity-extends-to-content
  (let [m0 {::A {:tx-data [{:db/ident ::my-attr
                            :db/valueType :db.type/string
                            :db/cardinality :db.cardinality/one}]
                 :dependencies []}
            ::B {:tx-data [{::my-attr "b"}]
                 :dependencies [::A]}}
        {db :db-after} (sut/migrate! *connection* m0 {})]
    (let [status (sut/status db)]
      (is (= -941745976 (-> status ::sut/hash)) status)))
  (let [m1 {::A {:tx-data [{:db/ident ::my-attr
                            :db/valueType :db.type/string
                            :db/cardinality :db.cardinality/one}]
                 :dependencies []}
            ::B {:tx-data [{::my-attr "bb"}]
                 :dependencies [::A]}}
        e (try (sut/migrate! *connection* m1 {})
               (catch java.lang.AssertionError e e))
        db (d/db *connection*)]
    (let [status (sut/status db)]
      (is (= -941745976 (-> status ::sut/hash)) status))))

(deftest identity-extends-to-structure
  (let [m0 {::A {:tx-data [{:db/ident ::my-attr
                            :db/valueType :db.type/string
                            :db/cardinality :db.cardinality/one}]
                 :dependencies []}
            ::B {:tx-data [{::my-attr "b"}]
                 :dependencies [::A]}}
        {db :db-after} (sut/migrate! *connection* m0 {})]
    (let [status (sut/status db)]
      (is (= -941745976 (-> status ::sut/hash)) status)))
  (let [m1 {::A {:tx-data [{:db/ident ::my-attr
                            :db/valueType :db.type/string
                            :db/cardinality :db.cardinality/one}]
                 :dependencies []}
            ::B {:tx-data [{::my-attr "b"}]
                 :dependencies [::A ::C]}
            ::C {:tx-data [{::my-attr "c"}]
                 :dependencies [::A]}}
        e (try (sut/migrate! *connection* m1 {})
               (catch java.lang.AssertionError e e))
        db (d/db *connection*)]
    (let [status (sut/status db)]
      (is (= -941745976 (-> status ::sut/hash)) status))))

(deftest epoch-roots-are-independent
  (binding [sut/*epoch* 0]
    (let [m0 {::A0 {:tx-data [{:db/ident ::my-attr0
                               :db/valueType :db.type/string
                               :db/cardinality :db.cardinality/one}]
                    :dependencies []}
              ::B0 {:tx-data [{::my-attr0 "b"}]
                    :dependencies [::A0]}}
          {db :db-after} (sut/migrate! *connection* m0 {})]
      (let [status (sut/status db)]
        (is (= -1445663044 (-> status ::sut/hash)) status))))
  (binding [sut/*epoch* 1]
    (let [m0 {::A0 {:tx-data [{:db/ident ::my-attr0
                               :db/valueType :db.type/string
                               :db/cardinality :db.cardinality/one}]
                    :dependencies []}
              ::B0 {:tx-data [{::my-attr0 "b"}]
                    :dependencies [::A0 ::C0]} ; w/o epoch independence, this would be a redefinition of ::B0
              ::C0 {:tx-data [{::my-attr0 "c"}]
                    :dependencies [::A0]}}
          {db :db-after} (sut/migrate! *connection* m0 {})]
      (let [status (sut/status db)]
        (is (= 2145712772 (-> status ::sut/hash)) status)))))

(deftest claim-existing-database
  (let [m0 {::A {:tx-data [{:db/ident ::my-attr
                            :db/valueType :db.type/string
                            :db/cardinality :db.cardinality/one}]
                 :dependencies []}
            ::B {:tx-data [{::my-attr "b"}]
                 :dependencies [::A]}}
        {db :db-after} (sut/migrate! *connection* m0 :claim-only? true)]
    (let [status (sut/status db)]
      (is (= -941745976 (-> status ::sut/hash)) status)
      (is (nil? (-> (d/pull db '[*] ::my-attr) :db/id))))))

(deftest assess
  (let [m0 (dissoc reference-migrations :st.schema/document-usage)
        m1 reference-migrations
        {db :db-after} (sut/migrate! *connection* m0 {})]
    (is (= [[-211898652 1013102628]
            {:common-count 27
             :only-remote #{}
             :only-local #{:st.schema/document-usage}}]
           (sut/assess db m1 {})))))

(deftest override-tx-instant
  (let [t #inst "2000-01-01"
        migrations {::A {:tx-data [{:db/id "datomic.tx"
                                    :db/txInstant t}
                                   {:db/ident ::my-attr
                                    :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one}]
                         :dependencies []}}]
    (let [{{tx "datomic.tx"} :tempids db :db-after} (sut/migrate! *connection* migrations :tx-instant t)]
      (is (= #:db{:txInstant t} (d/pull db [:db/txInstant] tx))))))

(deftest race-condition
  (let [migrations {::A {:tx-data [{:db/ident ::my-attr
                                    :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one}]
                         :dependencies []}}
        empty-history (#'sut/history! *connection* {})]
    (sut/migrate! *connection* migrations)
    (is (= {:tempids {}, :tx-data []}
           (with-redefs [sut/history! (constantly empty-history)]
             (sut/migrate! *connection* migrations))))))

(deftest tx-data-fn
  (let [migrations {::A {:tx-data [{:db/ident ::my-attr
                                    :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one}]
                         :dependencies []}
                    ::B {:tx-data-fn (fn [& args] [{::my-attr "b"}])
                         :dependencies [::A]}}]
    (let [{db :db-after} (sut/migrate! *connection* migrations)
          status (sut/status db)]
      (is (= -941745976 (-> status ::sut/hash)) status))))

(deftest step-fn
  (let [migrations {::A {:tx-data [{:db/ident ::my-attr
                                    :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one}]
                         :dependencies []}
                    ::B {:step-fn (fn [{done? :done? :as context}]
                                    (when (not done?) (assoc context :tx-data [{:db/ident ::stepped}] :done? true)))
                         :dependencies [::A]}}]
    (let [{db :db-after} (sut/migrate! *connection* migrations)
          status (sut/status db)]
      (is (= -50766689 (-> status ::sut/hash)) status)
      (is (= ::stepped (-> (d/pull db {:selector '[*] :eid ::stepped}) :db/ident))))))

(deftest subtree
  (let [migrations (sut/subtree reference-migrations :st.seeds/pjm-utilities)
        {db :db-after} (sut/migrate! *connection* migrations)
        status (sut/status db)]
    (is (= [:st.seeds/pjm-utilities
            :st.schema/pjm-utilities
            :st.schema/tx-metadata]
           (keys migrations)))
    (is (= -339030628 (-> status ::sut/hash)))))

(deftest specified-transact-fn
  (let [before-custom-transact {::A {:tx-data [{:db/ident       :audit/actor
                                                :db/valueType   :db.type/string
                                                :db/cardinality :db.cardinality/one}]}}
        _                      (sut/migrate! *connection* before-custom-transact)
        actor                  "some-user-running-tx"
        custom-transact        (fn add-audit
                                 [conn arg-map]
                                 (let [audit-tx (update arg-map :tx-data conj {:db/id       "datomic.tx"
                                                                               :audit/actor actor})]
                                   (d/transact conn audit-tx)))
        with-custom-transact   (assoc before-custom-transact ::B {:tx-data [{:db/ident       ::my-attr
                                                                             :db/valueType   :db.type/string
                                                                             :db/cardinality :db.cardinality/one}]})]
    (let [{{tx "datomic.tx"} :tempids db :db-after} (sut/migrate! *connection*
                                                                  with-custom-transact
                                                                  :transact-fn custom-transact)]
      (is (= {:audit/actor actor} (d/pull db [:audit/actor] tx))))))
