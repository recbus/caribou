(ns st.migrations.state+county-name
  "Re-assert composite tuple components for :st.county/state+county-name to
  ensure tuple values are properly populated."
  (:require [datomic.client.api :as d]))

(defn assert-tuple-components
  [{:keys [db batch-size pacing counter] :as context :or {pacing 5 counter 0}}]
  (let [es (d/q {:query '[:find (pull ?county [:db/id :st.county/state])
                          :in $
                          :where
                          , [?county :st.county/state]
                          , [?county :ansi.fips/county-name]
                          , [(missing? $ ?county :st.county/state+county-name)]]
                 :args [db]
                 :limit batch-size})
        tx-data (sequence (comp (map first)
                                (map (fn [{eid :db/id {sid :db/id} :st.county/state}]
                                       ;; only one component needs to be asserted to populate the tuple
                                       [:db/add eid :st.county/state sid])))
                          es)]
    (when (seq tx-data)
      (when (pos? counter) (Thread/sleep pacing))
      (-> context
          (assoc :tx-data tx-data)
          (assoc :counter (inc counter))))))
