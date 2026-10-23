(ns groundwork-postgres.queries.rows
  "Values in, rows out, with real types.

   In: an Instant goes in as a timestamptz; everything else as it is.
   Out: for each column of a result, its key and the way to read its value
   are chosen once, from its name and its Postgres type; every row reuses
   them. Values are real types: UUID, BigDecimal, java.time. In json and
   jsonb, keys shaped entity__field become :entity/field; other keys stay
   strings."
  (:require
   [clojure.string :as str]
   [groundwork-postgres.queries.names :as names]
   [jsonista.core :as json])
  (:import
   (java.sql ResultSet)
   (java.time Instant LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZoneOffset)))

(defn turn-param-value-into-jdbc-value
  "A param's value as JDBC takes it: an Instant becomes an OffsetDateTime
   in UTC, which PgJDBC sends as a timestamptz."
  [value]
  (if (instance? Instant value)
    (.atOffset ^Instant value ZoneOffset/UTC)
    value))

(defn- find-key-for-json-key [^String json-key]
  (or (when (str/includes? json-key "__") (names/find-key-for-column-name json-key))
      json-key))

(def ^:private json-mapper
  (json/object-mapper {:decode-key-fn find-key-for-json-key}))

(defn- make-column-reader-for-type
  "How to read one column, chosen from its Postgres type."
  [^String type-name]
  (case type-name
    ("json" "jsonb") (fn [^ResultSet rs i]
                       (some-> (.getString rs (int i)) (json/read-value json-mapper)))
    "timestamptz"    (fn [^ResultSet rs i] (.getObject rs (int i) OffsetDateTime))
    "timestamp"      (fn [^ResultSet rs i] (.getObject rs (int i) LocalDateTime))
    "date"           (fn [^ResultSet rs i] (.getObject rs (int i) LocalDate))
    "time"           (fn [^ResultSet rs i] (.getObject rs (int i) LocalTime))
    "timetz"         (fn [^ResultSet rs i] (.getObject rs (int i) OffsetTime))
    (if (str/starts-with? type-name "_")
      ;; a Postgres array: _int4, _text, … becomes a vector
      (fn [^ResultSet rs i] (some-> (.getArray rs (int i)) (#(.getArray ^java.sql.Array %)) vec))
      (fn [^ResultSet rs i] (.getObject rs (int i))))))

(defn- find-key-for-column-or-throw [column-name path]
  (or (names/find-key-for-column-name column-name)
      (throw (ex-info (str "Column " (pr-str column-name) " in " path " has no name groundwork-postgres can read."
                           " Name it in the SQL: … AS entity__field")
                      {:type :groundwork-postgres/bad-column :column column-name :file path}))))

(defn list-rows-in-result-set
  "Every row of a result as a map. A column without a readable name throws,
   naming the file."
  [^ResultSet rs path]
  (let [meta    (.getMetaData rs)
        indexes (range 1 (inc (.getColumnCount meta)))
        ks      (mapv #(find-key-for-column-or-throw (.getColumnLabel meta (int %)) path) indexes)
        readers (mapv #(make-column-reader-for-type (.getColumnTypeName meta (int %))) indexes)]
    (loop [rows (transient [])]
      (if (.next rs)
        (recur (conj! rows (into {} (map (fn [k reader i] [k (reader rs i)]) ks readers (map inc (range))))))
        (persistent! rows)))))
