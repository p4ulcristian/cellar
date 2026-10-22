(ns cellar.tables.desired
  "The table maps turned into the form catalog.clj reads from Postgres, so
   the two can be compared, and a table map turned into its row shape.

     {:table   :orders
      :columns [[:order/id      :uuid    {:primary-key? true :default :random-uuid}]
                [:order/shop-id :uuid    {:references :shop/id}]
                [:order/status  [:enum \"draft\" \"done\"]]
                [:order/note    :string  {:optional? true}]]
      :indexes [[:order/shop-id]]}"
  (:require
   [cellar.tables.gates :as gates]
   [cellar.tables.names :as names]
   [cellar.tables.triggers :as triggers]
   [cellar.tables.types :as types]))

(defn- find-table-of-primary-key
  "{:shop/id \"shops\"}: which table each one-column primary key is in."
  [table-maps]
  (into {}
        (for [{:keys [table columns]} table-maps
              [k _ options] columns
              :when (:primary-key? options)]
          [k (names/turn-table-key-into-table-name table)])))

(defn- turn-table-map-into-desired [tables-by-primary-key {:keys [table columns indexes gates on-change]}]
  (let [table-name (names/turn-table-key-into-table-name table)
        column     (fn [[k type {:keys [optional? default]}]]
                     (let [postgres-type (types/turn-type-into-postgres-type type)]
                       {:name      (names/turn-key-into-column-name k)
                        :type      postgres-type
                        :not-null? (not optional?)
                        :default   (some-> default (types/turn-default-into-shown-text postgres-type))
                        :default-sql (some-> default types/turn-default-into-sql)
                        :volatile-default? (types/is-default-volatile? default)}))]
    [table-name
     {:columns      (mapv column columns)
      :primary-key  (vec (for [[k _ options] columns :when (:primary-key? options)]
                           (names/turn-key-into-column-name k)))
      :foreign-keys (into {}
                          (for [[k _ {:keys [references]}] columns
                                :when references
                                :let [column-name (names/turn-key-into-column-name k)
                                      foreign-table (or (get tables-by-primary-key references)
                                                        (throw (ex-info (str table-name "." column-name " references "
                                                                             references ", which is no table's primary key")
                                                                        {:type :cellar/bad-reference
                                                                         :references references})))]]
                            [(names/make-foreign-key-name table-name column-name)
                             {:column column-name :foreign-table foreign-table
                              :foreign-column (names/turn-key-into-column-name references)}]))
      :uniques      (into {}
                          (for [[k _ {:keys [unique?]}] columns
                                :when unique?
                                :let [column-name (names/turn-key-into-column-name k)]]
                            [(names/make-unique-name table-name column-name) {:column column-name}]))
      :checks       (into {}
                          (for [[k type] columns
                                :let [values (types/find-enum-values type)]
                                :when values
                                :let [column-name (names/turn-key-into-column-name k)]]
                            [(names/make-enum-check-name table-name column-name)
                             {:column column-name :values values}]))
      :gated?       (some? gates)
      :policies     (if gates (gates/turn-gates-into-policies (names/turn-table-key-into-table-name table) gates) {})
      :triggers     (if on-change (triggers/turn-on-change-into-triggers table-name on-change) {})
      :indexes      (into {}
                          (for [index-keys indexes
                                :let [column-names (mapv names/turn-key-into-column-name index-keys)]]
                            [(names/make-index-name table-name column-names) {:columns column-names}]))}]))

(defn turn-table-maps-into-desired
  "Every table map in the catalog's form, by table name. A table named twice,
   or a reference to no table's primary key, throws."
  [table-maps]
  (let [table-names (map (comp names/turn-table-key-into-table-name :table) table-maps)]
    (when (not= (count table-names) (count (set table-names)))
      (throw (ex-info "A table is declared twice" {:type :cellar/table-twice :tables table-names}))))
  (let [tables-by-primary-key (find-table-of-primary-key table-maps)]
    (into {} (map #(turn-table-map-into-desired tables-by-primary-key %)) table-maps)))

(defn turn-table-into-row-shape
  "The malli shape of a table's rows, for wire: an optional column may be
   missing or nil."
  [{:keys [columns]}]
  (into [:map]
        (for [[k type {:keys [optional?]}] columns]
          (if optional?
            [k {:optional true} [:maybe type]]
            [k type]))))
