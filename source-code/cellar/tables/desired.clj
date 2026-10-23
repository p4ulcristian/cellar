(ns cellar.tables.desired
  "The table maps turned into the form catalog.clj reads from Postgres, so
   the two can be compared, and a table map turned into its row shape.

     {:table     :orders
      :columns   [[:order/id      :uuid   {:primary-key? true :default :random-uuid}]
                  [:order/shop-id :uuid   {:references :shop/id}]
                  [:order/note    :string {:optional? true}]]
      :indexes   [[:order/shop-id]]
      :references []
      :triggers  []
      :gates     nil
      :on-change nil}

   The maps are checked by cellar.settings/tables-shape before they come here."
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
              :let [primary-key (for [[k _ options] columns :when (:primary-key? options)] k)]
              :when (= 1 (count primary-key))]
          [(first primary-key) (names/turn-table-key-into-table-name table)])))

(defn- throw-bad-reference [message references]
  (throw (ex-info message {:type :cellar/bad-reference :references references})))

(defn- find-target-of-reference
  "The table map a reference over several columns points at: the one that
   has every :to column, its primary key among them. Throws when there is
   none, or when the columns don't pair up."
  [table-maps table-name {:keys [columns to] :as reference}]
  (let [has-column? (fn [table-map k] (some #(= k (first %)) (:columns table-map)))
        target      (first (filter (fn [table-map] (every? #(has-column? table-map %) to)) table-maps))
        primary-key (when target (for [[k _ options] (:columns target) :when (:primary-key? options)] k))
        own         (first (filter #(= table-name (names/turn-table-key-into-table-name (:table %))) table-maps))]
    (cond
      (not= (count columns) (count to))
      (throw-bad-reference (str table-name "'s reference " (pr-str reference) " pairs " (count columns)
                                " columns with " (count to)) reference)
      (some #(not (has-column? own %)) columns)
      (throw-bad-reference (str table-name "'s reference " (pr-str reference) " names a column it doesn't have") reference)
      (nil? target)
      (throw-bad-reference (str table-name "'s reference " (pr-str reference) " points at no table that has every :to column")
                           reference)
      (not (and (seq primary-key) (every? (set to) primary-key)))
      (throw-bad-reference (str table-name "'s reference " (pr-str reference) " must name its target's primary key among :to")
                           reference)
      :else target)))

(defn- turn-references-into-foreign-keys
  "{name {:columns :foreign-table :foreign-columns :on-update}}: a column's
   :references (on update refused) and the table's references over
   several columns."
  [table-maps tables-by-primary-key table-name {:keys [columns references]}]
  (merge
   (into {}
         (for [[k _ {references-key :references}] columns
               :when references-key
               :let [column-name   (names/turn-key-into-column-name k)
                     foreign-table (or (get tables-by-primary-key references-key)
                                       (throw-bad-reference (str table-name "." column-name " references "
                                                                 references-key ", which is no table's one-column primary key")
                                                            references-key))]]
           [(names/make-foreign-key-name table-name [column-name])
            {:columns [column-name] :foreign-table foreign-table
             :foreign-columns [(names/turn-key-into-column-name references-key)] :on-update :refuse}]))
   (into {}
         (for [{:keys [to on-update] :as reference} references
               :let [target       (find-target-of-reference table-maps table-name reference)
                     column-names (mapv names/turn-key-into-column-name (:columns reference))]]
           [(names/make-foreign-key-name table-name column-names)
            {:columns column-names :foreign-table (names/turn-table-key-into-table-name (:table target))
             :foreign-columns (mapv names/turn-key-into-column-name to) :on-update on-update}]))))

(defn- check-triggers
  "A trigger's :update-of names columns of its table, and only with
   :update among its events; throws otherwise."
  [table-name {:keys [columns triggers]}]
  (doseq [{:keys [events update-of] :as trigger} triggers
          :when update-of]
    (when-not (contains? events :update)
      (throw (ex-info (str table-name "'s trigger " (pr-str trigger) " has :update-of without :update among its events")
                      {:type :cellar/bad-trigger :trigger trigger})))
    (when-let [unknown (seq (remove (set (map first columns)) update-of))]
      (throw (ex-info (str table-name "'s trigger " (pr-str trigger) " names " (pr-str (vec unknown)) ", no column of it")
                      {:type :cellar/bad-trigger :trigger trigger})))))

(defn- turn-table-map-into-desired [table-maps tables-by-primary-key {:keys [table columns indexes gates on-change triggers]}]
  (let [table-name (names/turn-table-key-into-table-name table)
        column     (fn [[k type {:keys [optional? default]}]]
                     (let [postgres-type (types/turn-type-into-postgres-type type)]
                       {:name      (names/turn-key-into-column-name k)
                        :type      postgres-type
                        :not-null? (not optional?)
                        :default   (some-> default (types/turn-default-into-shown-text postgres-type))
                        :default-sql (some-> default types/turn-default-into-sql)
                        :volatile-default? (types/is-default-volatile? default)}))
        table-map  (first (filter #(= table (:table %)) table-maps))]
    (check-triggers table-name table-map)
    [table-name
     {:columns      (mapv column columns)
      :primary-key  (vec (for [[k _ options] columns :when (:primary-key? options)]
                           (names/turn-key-into-column-name k)))
      :foreign-keys (turn-references-into-foreign-keys table-maps tables-by-primary-key table-name table-map)
      :uniques      (into {}
                          (for [[k _ {:keys [unique?]}] columns
                                :when unique?
                                :let [column-name (names/turn-key-into-column-name k)]]
                            [(names/make-unique-name table-name [column-name]) {:columns [column-name]}]))
      :checks       (into {}
                          (for [[k type] columns
                                :let [values (types/find-enum-values type)]
                                :when values
                                :let [column-name (names/turn-key-into-column-name k)]]
                            [(names/make-enum-check-name table-name column-name)
                             {:column column-name :values values}]))
      :gated?       (some? gates)
      :policies     (if gates (gates/turn-gates-into-policies (names/turn-table-key-into-table-name table) gates) {})
      :triggers     (merge (if on-change (triggers/turn-on-change-into-triggers table-name on-change) {})
                           (triggers/turn-declared-triggers-into-triggers table-name triggers))
      :indexes      (into {}
                          (for [index-keys indexes
                                :let [column-names (mapv names/turn-key-into-column-name index-keys)]]
                            [(names/make-index-name table-name column-names) {:columns column-names}]))}]))

(defn- add-unique-keys-of-references
  "Postgres points a reference only at columns unique together: every
   reference over several columns gives its target a unique key on them.
   Its target's primary key is among them, so it holds for every row."
  [desired]
  (reduce (fn [desired {:keys [foreign-table foreign-columns]}]
            (if (and (< 1 (count foreign-columns)) (contains? desired foreign-table))
              (assoc-in desired [foreign-table :uniques (names/make-unique-name foreign-table foreign-columns)]
                        {:columns foreign-columns})
              desired))
          desired
          (mapcat (comp vals :foreign-keys) (vals desired))))

(defn turn-table-maps-into-desired
  "Every table map in the catalog's form, by table name. A table named
   twice, or a reference to no table's primary key, throws."
  [table-maps]
  (let [table-names (map (comp names/turn-table-key-into-table-name :table) table-maps)]
    (when (not= (count table-names) (count (set table-names)))
      (throw (ex-info "A table is declared twice" {:type :cellar/table-twice :tables table-names}))))
  (let [tables-by-primary-key (find-table-of-primary-key table-maps)]
    (add-unique-keys-of-references
     (into {} (map #(turn-table-map-into-desired table-maps tables-by-primary-key %)) table-maps))))

(defn turn-table-into-row-shape
  "The malli shape of a table's rows, for wire: an optional column may be
   missing or nil."
  [{:keys [columns]}]
  (into [:map]
        (for [[k type {:keys [optional?]}] columns]
          (if optional?
            [k {:optional true} [:maybe type]]
            [k type]))))
