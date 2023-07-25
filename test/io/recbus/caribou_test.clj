(ns io.recbus.caribou-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [datomic.client.api :as d]
            [datomic.dev-local :as dl]
            [io.recbus.caribou :as sut])
  (:import (java.net URI URL)))

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
        client (d/client {:server-type :dev-local
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
    (let [{db :db-after} (sut/execute! *connection* migrations {})]
      (is (= {::sut/root #{::A},
	      ::A #{}}
             (sut/history db))))))

(deftest persisted-hash-is-stable
  (let [migrations {::A {:tx-data [{:db/ident ::my-attr
                                    :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one}]
                         :dependencies []}}]
    (let [{db :db-after} (sut/execute! *connection* migrations {})]
      (is (= -1096714776 (-> (sut/status db) ::sut/hash))))))

(deftest migrate-reference
  (let [{db :db-after} (sut/execute! *connection* reference-migrations {})]
    (is (= -211898652 (-> (sut/status db) ::sut/hash)))))

(deftest migrate-reference-in-two-steps
  (let [m0 (select-keys reference-migrations [:st.schema/tx-metadata :st.schema/user :st.schema/generator])
        m1 reference-migrations
        {db :db-after} (sut/execute! *connection* m0 {})
        {db :db-after} (sut/execute! *connection* reference-migrations {})]
    (is (= -211898652 (-> (sut/status db) ::sut/hash)))))

(deftest migrations-are-independent-transactions
  (let [m0 {::A {:tx-data [{:db/ident ::my-attr
                            :db/valueType :db.type/string
                            :db/cardinality :db.cardinality/one}]
                 :dependencies []}
            ::B {:tx-data [{::nothing "a"}]
                 :dependencies [::A]}}
        e (try (sut/execute! *connection* m0 {})
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
        {db :db-after} (sut/execute! *connection* m0 {})]
    (let [status (sut/status db)]
      (is (= -941745976 (-> status ::sut/hash)) status)))
  (let [m1 {::A {:tx-data [{:db/ident ::my-attr
                            :db/valueType :db.type/string
                            :db/cardinality :db.cardinality/one}]
                 :dependencies []}
            ::B {:tx-data [{::my-attr "bb"}]
                 :dependencies [::A]}}
        e (try (sut/execute! *connection* m1 {})
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
        {db :db-after} (sut/execute! *connection* m0 {})]
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
        e (try (sut/execute! *connection* m1 {})
               (catch java.lang.AssertionError e e))
        db (d/db *connection*)]
    (let [status (sut/status db)]
      (is (= -941745976 (-> status ::sut/hash)) status))))
