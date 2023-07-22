(ns st.migrations.ansi-counties
  "Process standards-based data for US counties and county equivalents.  The source of this
  data is the US Census Bureau who provide the ANSI standard data on their website as a
  downloadable text file described as \"County and County Equivalents\""
  {:source "https://www.census.gov/library/reference/code-lists/ansi.html#county"}
  (:require [clojure.data.csv :refer [read-csv]]
            [clojure.java.io :as io]))

;; References:
;; https://www.census.gov/library/reference/code-lists/ansi.html#county
;; https://www.usgs.gov/us-board-on-geographic-names/domestic-names

(defn csv->clojure
  [reader]
  (let [data (read-csv reader :separator \,)
        header [:usps/state-abbreviation :ansi.fips/state-numeric :ansi.fips/county-numeric :ansi.fips/county-name :ansi.fips/classfp]]
    (sequence (comp (map (fn [row] (zipmap header row)))) data)))

(defn clojure->tx
  [{:usps/keys [state-abbreviation] :ansi.fips/keys [state-numeric county-numeric county-name classfp] :as county}]
  {:st.county/state [:ansi.fips/state-numeric state-numeric]
   :ansi.fips/county-numeric county-numeric :ansi.fips/county-name county-name :ansi.fips/classfp classfp})

(defn tx-data
  [{data :data}]
  (with-open [r (io/reader data)]
    (into () (map clojure->tx) (-> r csv->clojure))))
