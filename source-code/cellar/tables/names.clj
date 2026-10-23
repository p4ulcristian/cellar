(ns cellar.tables.names
  "A key's column, a table key's table, and the names cellar gives what it
   makes, all lowercase, words joined by _.

     :order/shop-id       ->  \"order__shop_id\"
     :orders              ->  \"orders\"               (the public folder)
     :billing/invoices    ->  \"billing.invoices\"     (a folder of its own)
     (make-identifier \"orders\" \"order__shop_id\" \"fkey\")  ->  \"orders_order__shop_id_fkey\""
  (:require
   [clojure.string :as str]))

(def ^:private word #"[a-z][a-z0-9]*(?:-[a-z0-9]+)*")

(defn- dashes-into-underscores [s]
  (str/replace s "-" "_"))

(defn turn-key-into-column-name
  ":order/shop-id -> \"order__shop_id\"; a key outside the rules throws."
  [k]
  (when-not (and (keyword? k)
                 (re-matches word (name k))
                 (or (nil? (namespace k)) (re-matches word (namespace k))))
    (throw (ex-info (str "Not a column key cellar can name: " (pr-str k))
                    {:type :cellar/bad-name :key k})))
  (if-let [entity (namespace k)]
    (str (dashes-into-underscores entity) "__" (dashes-into-underscores (name k)))
    (dashes-into-underscores (name k))))

(defn turn-table-key-into-table-name
  ":orders -> \"orders\"; :billing/invoices -> \"billing.invoices\": a
   key's namespace is the Postgres schema (folder) the table is in. A key
   outside the rules throws."
  [k]
  (when-not (and (keyword? k)
                 (re-matches word (name k))
                 (or (nil? (namespace k)) (re-matches word (namespace k))))
    (throw (ex-info (str "Not a table key cellar can name: " (pr-str k))
                    {:type :cellar/bad-name :key k})))
  (str (some-> (namespace k) dashes-into-underscores (str "."))
       (dashes-into-underscores (name k))))

(defn find-folder-of-table
  "\"billing.invoices\" -> \"billing\"; \"orders\" -> \"public\"."
  [table-name]
  (if (str/includes? table-name ".") (first (str/split table-name #"\.")) "public"))

(defn find-table-part
  "\"billing.invoices\" -> \"invoices\"; \"orders\" -> \"orders\"."
  [table-name]
  (last (str/split table-name #"\.")))

(defn qualify-index-name
  "An index lives in its table's folder: (qualify-index-name \"billing.invoices\" \"x_idx\") -> \"billing.x_idx\"."
  [table-name index-name]
  (if (str/includes? table-name ".") (str (find-folder-of-table table-name) "." index-name) index-name))

(defn make-identifier
  "Parts joined by _. Postgres cuts names past 63 characters, so a longer
   one ends in a hash of the whole instead."
  [& parts]
  (let [joined (str/replace (str/join "_" parts) "." "_")]
    (if (<= (count joined) 63)
      joined
      (str (subs joined 0 54) "_" (format "%08x" (bit-and (hash joined) 0xffffffff))))))

(defn make-foreign-key-name
  "A reference's name: its table and its columns (one or several), then fkey."
  [table-name column-names]
  (apply make-identifier table-name (concat column-names ["fkey"])))
(defn make-unique-name
  "A unique key's name: its table and its columns (one or several), then key."
  [table-name column-names]
  (apply make-identifier table-name (concat column-names ["key"])))
(defn make-trigger-name
  "A declared trigger's name: its table, then a hash of its SQL."
  [table-name body]
  (make-identifier (find-table-part table-name) "trigger" (format "%08x" (bit-and (hash body) 0xffffffff))))
(defn make-enum-check-name [table-name column-name] (make-identifier table-name column-name "enum"))
(defn make-check-name
  "A table's check: its table, then a hash of its condition, so a changed
   condition is another check."
  [table-name condition]
  (make-identifier table-name "check" (format "%08x" (bit-and (hash condition) 0xffffffff))))
(defn make-not-null-check-name [table-name column-name] (make-identifier table-name column-name "not_null"))
(defn make-index-name [table-name column-names] (apply make-identifier table-name (concat column-names ["idx"])))
