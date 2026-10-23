(ns cellar.tables.types
  "A column's type in wire's words as Postgres's type, and a default as
   SQL. How Postgres prints a default back is asked of it
   (cellar.tables.probe), not foretold.")

(def ^:private postgres-types
  ;; as format_type names them, which is also valid in CREATE TABLE
  {:uuid            "uuid"
   :int             "bigint"
   :decimal         "numeric"
   :double          "double precision"
   :string          "text"
   :boolean         "boolean"
   :time/instant    "timestamp with time zone"
   :time/local-date "date"})

(defn find-enum-values
  "The values of [:enum \"a\" \"b\"], or nil for another type."
  [type]
  (when (and (vector? type) (= :enum (first type)))
    (vec (rest type))))

(defn turn-type-into-postgres-type
  "The Postgres type of a column type; an enum is text with a check."
  [type]
  (cond
    (find-enum-values type) "text"
    (contains? postgres-types type) (get postgres-types type)
    :else (throw (ex-info (str "No Postgres type for the column type " (pr-str type))
                          {:type :cellar/unknown-type :column-type type}))))

(defn quote-text
  "'it''s' : a SQL text literal."
  [s]
  (str "'" (.replace ^String (str s) "'" "''") "'"))

(defn turn-default-into-sql
  "A column's :default as SQL: :random-uuid, :now, a number, true/false, text."
  [default]
  (cond
    (= :random-uuid default) "gen_random_uuid()"
    (= :now default)         "now()"
    (boolean? default)       (str default)
    (number? default)        (if (neg? default) (str "(" default ")") (str default))
    (string? default)        (quote-text default)
    :else (throw (ex-info (str "Not a default cellar can write: " (pr-str default))
                          {:type :cellar/bad-default :default default}))))

(defn is-default-volatile?
  "Whether Postgres computes the default anew for each row, which makes
   adding the column to a table with rows rewrite the table."
  [default]
  (= :random-uuid default))
