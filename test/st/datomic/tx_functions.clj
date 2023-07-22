(ns st.datomic.tx-functions
  (:require [datomic.client.api :as d]))

(defn nym-type [e] (condp = (type e)
                     clojure.lang.Keyword :db-ident
                     clojure.lang.PersistentVector :lookup-ref
                     java.lang.Long :eid
                     java.lang.String :tempid))

(def vtype (comp :db/ident :db/valueType #(d/pull %1 '[:db/valueType] %2)))

(defn- resolve-composite-refs
  [db v-or-nym]
  (if-let [[identifier value] (when (= :lookup-ref (nym-type v-or-nym)) v-or-nym)]
    [identifier (if-let [t-attrs (-> (d/pull db '[:db/tupleAttrs] identifier) :db/tupleAttrs)]
                  (mapv (fn [t-attr v] (if (= :db.type/ref (vtype db t-attr))
                                         (let [rid (-> (d/pull db '[:db/id] v) :db/id)]
                                           (assert rid (str "Lookup ref cannot be resolved" v-or-nym))
                                           rid)
                                         v))
                        t-attrs value)
                  value)]
    v-or-nym))

;; This should be possible without a database function, but Datomic composite tuples
;; are bafflingly limited with ref types and unique identity/value.
;; Reference: https://gist.github.com/robert-stuttaford/e329470c1a77712d7c4ab3580fe9aaa3
;; Reference: https://forum.datomic.com/t/troubles-with-upsert-on-composite-tuples/1355
(defn t-ref
  "Return equivalent transaction data to the given `[op e a v]` where any constituent lookup refs of
  composite tuple lookup-refs are resolved into eids.  This works around a known limitation of
  composite tuples combined with unique values references"
  [db [op e a v]]
  (let [e (resolve-composite-refs db e)
        v (if (= :db.type/ref (vtype db a))
            (resolve-composite-refs db v)
            v)]
    [[op e a v]]))
