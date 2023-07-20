(ns io.recbus.caribou.acyclic-digraph
  (:require [clojure.set :as set]))

;; https://en.wikipedia.org/wiki/Topological_sorting#Algorithms
;; https://gist.github.com/alandipert/1263783
(defn roots
  [edges]
  (reduce (fn [acc [v0 v1s]] (reduce (fn [acc v1] (disj acc v1)) acc v1s))
          (set (keys edges))
          edges))

(defn- normalize
  "Normalize the acyclic digraph represented by the `edges` map whose keys
  are 'from' vertices and whose values are the associated sets of 'to' vertices.
  Return the normalized edges, including the synthesized `root`, which connects
  all unconnected subgraphs."
  [edges root]
  (let [edges (dissoc edges root)
        g (reduce (fn [edges [vin vouts]]
                    (reduce (fn [edges vout]
                              (update edges vout (fnil identity #{}))) edges vouts))
                  edges
                  edges)]
    (assoc g root (roots g))))

(defn gdiff
  [edgesL edgesR]
  (let [vLs (set (keys edgesL))
        vRs (set (keys edgesR))]
    [(set/difference vLs vRs) (set/intersection vLs vRs) (set/difference vRs vLs)]))

(defn- pluck
  "Pluck the leaf vertex from the DAG `g` and return the modified DAG."
  [g leaf]
  (let [g (dissoc g leaf)]
    (reduce #(update %1 %2 disj leaf) g (keys g))))

(defn- find-first [pred coll] (some (fn [x] (when (pred x) x)) coll))

;; TODO: https://cs.stackexchange.com/questions/16848/topological-sort-equivalence
(defn topological-sort
  "Using Kahn's method, return a topological sort of the acyclic digraph `dag`.
  The `dag` maps a node (a non-nil value) to a possibly empty collection of its
  downstream neighbors.  If `dag` has cycles or lacks an entry for each
  referenced vertex (including empty entries for leaves) then nil is returned."
  [dag]
  (let [identify-leaves (fn [dag] (into #{} (comp (remove (comp seq val)) (map key)) dag))
        pick (fn [candidates] (let [leaf (first candidates)]
                                [leaf (disj candidates leaf)]))]
    (loop [dag dag out [] leaves (identify-leaves dag)]
      (if (empty? leaves)
        (when (empty? dag) out) ; nil if cyclic or dag lacks a degenerate entry for a leaf vertex
        (let [[leaf leaves] (pick leaves)
              dag (pluck dag leaf)
              leaves+ (identify-leaves dag)]
          (recur dag (conj out leaf) (set/union leaves leaves+)))))))
