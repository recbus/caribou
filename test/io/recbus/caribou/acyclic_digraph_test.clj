(ns io.recbus.caribou.acyclic-digraph-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [io.recbus.caribou.acyclic-digraph :as sut]))

(deftest sort-dag
  (let [g {}]
    (is (= [] (sut/topological-sort g))))
  (let [g {:a #{}}]
    (is (= [:a] (sut/topological-sort g))))
  (let [g {:a #{:b}
           :b #{:c}
           :c #{:d}
           :d #{}}]
    (is (= [:d :c :b :a] (sut/topological-sort g))))
  (let [g {:a #{:b}
           :b #{:c}
           :c #{:d :e}
           :d #{}
           :e #{}}]
    (is (#{[:e :d :c :b :a]
           [:d :e :c :b :a]} (sut/topological-sort g))))
  (let [g {:a #{:b}
           :b #{:c}
           :c #{:d :e}
           :d #{:f}
           :e #{:f}
           :f #{}}]
    (is (#{[:f :e :d :c :b :a]
           [:f :d :e :c :b :a]} (sut/topological-sort g))))
  (let [g {:a #{:aa}
           :b #{:bb}
           :aa #{}
           :bb #{}}
        s (sut/topological-sort g)]
    (is (#{[:aa :a :bb :b]
           [:aa :bb :a :b]
           [:aa :bb :b :a]
           [:bb :b :aa :a]
           [:bb :aa :b :a]
           [:bb :aa :a :b]} s))))

(deftest cyclic-dag-sort
  (let [g {:a #{:b}
           :b #{:a}}]
    (is (nil? (sut/topological-sort g)))))

(deftest un-normalized-dag-sort
  (let [g {:a #{:b}}]
    (is (nil? (sut/topological-sort g)))))

(deftest subgraph
  (let [g {:a #{:b}
           :b #{:c}
           :c #{:d :e}
           :d #{:f}
           :e #{:f}
           :f #{}
           :g #{:c}}]
    (is (= {:c #{:d :e}
            :d #{:f}
            :e #{:f}
            :f #{}}
           (sut/subgraph g :c)))))
