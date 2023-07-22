(ns st.datomic
  "A collection of Datomic transaction functions, query functions, xforms, rules
  and helpers.  This namespace is just a facade for transaction functions, query
  functions and xforms, which are defined elsewhere."
  {:reference "https://docs.datomic.com/cloud/ions/ions-reference.html#entry-points"})

;; Poor-man's potemkin
(defn- proxy-vars
  "Add proxy vars in this namespace that front all public vars in the list of namespace symbols `ns-syms`."
  [ns-syms]
  (doseq [ns-sym ns-syms]
    (require ns-sym)
    (doseq [[psym pvar] (ns-publics ns-sym)]
      (intern *ns* psym @pvar))))

(proxy-vars '(st.datomic.tx-functions))
#_(proxy-vars '(st.datomic.query-functions))

(def rules
  '[[(county-by-qualified-county-numeric ?qualified-county-numeric ?county)
     [(st.datomic/county-pair ?qualified-county-numeric) [?state-numeric ?county-numeric]]
     [?state-eid :ansi.fips/state-numeric ?state-numeric]
     [(tuple ?state-eid ?county-numeric) ?s+cn-tuple]
     [?county :st.county/state+county-numeric ?s+cn-tuple]]

    [(county-by-state-abbreviation-and-county-name ?state-abbreviation ?county-name ?county)
     [?state :usps/state-abbreviation ?state-abbreviation]
     [(tuple ?state ?county-name) ?t]
     [?county :st.county/state+county-name ?t]]])

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
