(ns cellar.queries.names
  "A column's name turned into its key, exactly:

     \"order__created_at\"  ->  :order/created-at
     \"count\"              ->  :count

   A part is lowercase letters and digits, words joined by single
   underscores. Anything else is not a name cellar reads: nil, nothing
   guessed."
  (:require
   [clojure.string :as str]))

(def ^:private column-part #"[a-z][a-z0-9]*(?:_[a-z0-9]+)*")

(defn- underscores-into-dashes [s]
  (str/replace s "_" "-"))

(defn find-key-for-column-name
  "The keyword for a column name, or nil when it isn't one."
  [column-name]
  (when (string? column-name)
    (if-let [i (str/index-of column-name "__")]
      (let [entity (subs column-name 0 i)
            field  (subs column-name (+ i 2))]
        (when (and (re-matches column-part entity) (re-matches column-part field))
          (keyword (underscores-into-dashes entity) (underscores-into-dashes field))))
      (when (re-matches column-part column-name)
        (keyword (underscores-into-dashes column-name))))))

(defn find-key-for-table
  "A table's key from its folder (schema) and name: \"public\" \"orders\" ->
   :orders; \"billing\" \"invoices\" -> :billing/invoices.
   nil when either isn't a name cellar reads."
  [folder table-name]
  (when (and (re-matches column-part folder) (re-matches column-part table-name))
    (if (= "public" folder)
      (keyword (underscores-into-dashes table-name))
      (keyword (underscores-into-dashes folder) (underscores-into-dashes table-name)))))
