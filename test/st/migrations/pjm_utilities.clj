(ns st.migrations.pjm-utilities
  "Process utility and balancing authority data by county.  The source of this data is
  PJM-GATS who provide the data as a CSV file on their website described as \"Utilities\""
  {:source "https://gats.pjm-eis.com/gats2/AccountReports/Utilities"}
  (:require [camel-snake-kebab.core :as csk]
            [clojure.data.csv :refer [read-csv]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;;; Notes:
;;; 1. A utility can be associated with multiple balancing authorities (e.g. "City of Benton - (AR)")
;;; 2. A county can be associated with multiple balancing authorities (e.g. "Forsyth"+"NC")
;;; 3. A county can be associated with multiple utilities (e.g. "Rockingham County"+"VA")

(def csv->clojure
  (memoize (fn csv->clojure [resource]
             (let [[header & data] (read-csv resource :separator \,)
                   header (map csk/->kebab-case-keyword header)]
               (sequence (comp (map (fn [row] (zipmap header row)))) data)))))

(def fictitious-duplicates
  "A mapping of duplicated PJM counties/county-equivalents with fictitious names to their
  (more standard) PJM names."
  {"GA" {"De Kalb" "DeKalb"} ; the proper spelling is DeKalb, which PJM includes separately
   ;; NB: There is no Alexandria County in Virginia -it was renamed to Arlington County in 1920.
   ;;     The independent Alexandria city is a county-equivalent and is included separately.
   "VA" {"Alexandria County" "Alexandria City"}
   ;; Cities in WI are not county-equivalent per the US Census Bureau and thus
   ;; the ANSI/FIPS data does not include a separate city.
   "WI" {"St Croix" "St. Croix County"
         "Trempealeau" "Trempealeau County"}})

(def exceptions
  "A mapping of inconsistent, misspelled and unusually capitalized/punctuated PJM county names to
  their ANSI/FIPS standard name."
  {"GA" {"Chattahooche" "Chattahoochee"
         "Mcintosh" "McIntosh"
         "RICHMOND" "Richmond"}
   "IL" {"La Salle" "LaSalle"}
   "IN" {"De Kalb" "DeKalb"
         "La Porte" "LaPorte"
         "Lagrange" "LaGrange"}
   "LA" {"DeSoto" "De Soto"
         "St  Helena" "St Helena"}
   "MD" {"Prince Georges" "Prince George's"
         "Queen Annes" "Queen Anne's"
         "St Marys" "St Mary's"}
   "MO" {"St Louis City" "St. Louis City"}
   "ND" {"Mcintosh" "McIntosh"}})

(defn pjm->ansi-fips
  "Translate the (mis-spelled, inconsistent, non-standard) PJM county name to
   the standard ANSI/FIPS county name."
  [[state county]]
  (let [cname (as-> county %
                (get-in fictitious-duplicates [state %] %)
                (get-in exceptions [state %] %)
                (str/replace % #"^St " "St. ")
                (cond
                  (re-find #" City$" %) (str/replace % #" City$" " city")
                  true (case state
                         "LA" (str % " Parish")
                         ("DC" "VA" "WI" "WV") %
                         (str % " County"))))]
    [state cname]))

(defn- promote
  [{:keys [state county utility-id balancing-authority-code] :as u}]
  (-> u
      (update :utility-id parse-long)
      (assoc :county (pjm->ansi-fips [state county]))
      (dissoc :state)))

(defn- normalize
  ([] {:cubs () :utilities {} :balancing-authorities {}})
  ([acc] acc)
  ([acc {:keys [county utility utility-id balancing-authority balancing-authority-code]}]
   (let [utility {:id utility-id :name utility :county county}
         balancing-authority {:code balancing-authority-code :name balancing-authority}]
     (-> acc
         (update :utilities assoc utility-id utility)
         (update :balancing-authorities assoc balancing-authority-code balancing-authority)
         (update :cubs conj [county utility-id balancing-authority-code])))))

(defn- utility->tx-data
  [{:keys [id name balancing-authority] [state-abbreviation county-name] :county}]
  {:pjm.utility/id id
   :pjm.utility/name name})

(defn- balancing-authority->tx-data
  [{:keys [code name]}]
  {:pjm.balancing-authority/name name
   :pjm.balancing-authority/code code})

(defn- cub-tuple->tx-data
  [[[sabbrev county-name :as county] utility-id balancing-authority-code]]
  (let [tid (format "%d %s %s" utility-id balancing-authority-code county)]
    [`(~'st.datomic/t-ref
       [:db/add ~tid :st.cub/county [:st.county/state+county-name [[:usps/state-abbreviation ~sabbrev] ~county-name]]])
     [:db/add tid :st.cub/utility [:pjm.utility/id utility-id]]
     [:db/add tid :st.cub/balancing-authority [:pjm.balancing-authority/code balancing-authority-code]]]))

(defn- tx-data
  [data]
  (with-open [r (io/reader data)]
    (transduce (map promote) normalize (-> r csv->clojure))))

(defn balancing-authorities-tx-data
  [{data :data}]
  (let [{:keys [balancing-authorities]} (tx-data data)]
    (map balancing-authority->tx-data (vals balancing-authorities))))

(defn utilities-tx-data
  [{data :data}]
  (let [{:keys [utilities]} (tx-data data)]
    (map utility->tx-data (vals utilities))))

(defn cubs-tx-data
  [{data :data}]
  (let [{:keys [cubs]} (tx-data data)]
    (mapcat cub-tuple->tx-data cubs)))
