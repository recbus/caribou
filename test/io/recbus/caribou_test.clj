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
  (let [migrations {::A {:tx-data [{:db/ident ::a
                                    :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one}]
                         :dependencies []}}]
    (let [{{db :db-after} ::A} (sut/execute! *connection* migrations {})]
      (is (= {::sut/root #{::A},
	      ::A #{}}
             (sut/history db))))))

(deftest persisted-hash-is-stable
  (let [migrations {::A {:tx-data [{:db/ident ::a
                                    :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one}]
                         :dependencies []}}]
    (let [{{db :db-after} ::A} (sut/execute! *connection* migrations {})]
      (is (= [1732701487 {}] (sut/status (d/db *connection*)))))))

(deftest migrate-reference
  (let [{{db :db-after} ::A} (sut/execute! *connection* reference-migrations {})]
    (is (= [-211898652 {}] (sut/status (d/db *connection*))))))

#_{:st.update/state+county-name {:step-fn      st.migrations.state+county-name/assert-tuple-components
                                 :context      {:batch-size 1000}
                                 :dependencies [:st.schema/state+county-name]}}

{::B {:tx-data-fn (constantly {:db/ident ::b
                               :db/valueType :db.type/string
                               :db/cardinality :db.cardinality/one})}}
