(ns io.recbus.caribou-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [datomic.client.api :as d]
            [datomic.dev-local :as dl]
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
        client (d/client {:server-type :dev-local
                          :storage-dir :mem
                          :system system})
        db-name "caribou"]
    (d/create-database client {:db-name db-name})
    (binding [*connection* (d/connect client {:db-name db-name})]
      (try (f)
           (finally
             (dl/release-db {:db-name db-name :system system}) ; this appears to work on non dev-local dbs as well
             (d/delete-database client {:db-name db-name}))))))

(use-fixtures :each db-setup)

(deftest t
  (let [migrations {::A {:tx-data [{:db/ident ::a
                                    :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one}]
                         :dependencies []}}]
    (let [{{db :db-after} ::A} (sut/execute! *connection* migrations {})]
      (is (= {::sut/root #{::A},
	      ::A #{}}
             (sut/history db))))))
