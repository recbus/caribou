(ns io.recbus.caribou.datomic-helpers
  "A collection of Datomic helpers")

(defn rf-txs
  "A reducing function that summarizes a sequence of Datomic transaction results
  into a meta result with the same shape but the following semantic differences:
   * :tempids is the merge of all the tempids in all transactions.  For
     duplicates, the last one in wins.
   * :tx-data is the concatenation of the tx-data from constituent transactions.
   * :tx-before is the database before the *first* transaction.
   * :tx-after is the database after the *last* transaction."
  ([] {:tempids {} :tx-data []})
  ([acc] (update acc :tx-data vec))
  ([acc {:keys [db-after db-before tx-data tempids]}]
   (-> acc
       (update :tx-data concat tx-data)
       (assoc :db-after db-after)
       (update :db-before (fn [db] (or db db-before)))
       (update :tempids merge tempids))))
