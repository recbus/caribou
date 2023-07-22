(ns st.migrations.ansi-states
  "Process standards-based data for US States and state equivalents.  The source of this
  data is the US Census Bureau who provide the ANSI standard data on their website as a
  downloadable text file described as \"State and State Equivalents\""
  {:source-parent  "https://www.census.gov/library/reference/code-lists/ansi.html"
   :source "https://www.census.gov/library/reference/code-lists/ansi/ansi-codes-for-states.html"}
  (:require [clojure.data.csv :refer [read-csv]]
            [clojure.java.io :as io]))

;; References:
;; https://www.usgs.gov/us-board-on-geographic-names/domestic-names

(defn csv->clojure
  [reader]
  (let [[header & data] (read-csv reader :separator \|)
        header (map keyword header)]
    (sequence (comp (map (fn [row] (zipmap header row)))) data)))

(defn clojure->tx
  [{:keys [STATE STUSAB STATE_NAME STATENS] :as state}]
  {:ansi.fips/state-numeric STATE :usps/state-abbreviation STUSAB :usps/state-name STATE_NAME :usgs.bgn.gnis/identifier STATENS})

(defn tx-data
  [{data :data}]
  (with-open [r (io/reader data)]
    (into () (map clojure->tx) (-> r csv->clojure))))
