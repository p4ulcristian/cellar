(ns cellar.tables.catalog
  "What Postgres has, read from its catalog, in the same form desired.clj
   makes from the maps:

     {\"orders\" {:columns      [{:name \"order__id\" :type \"uuid\" :not-null? true :default \"gen_random_uuid()\"} …]
                 :primary-key  [\"order__id\"]
                 :foreign-keys {\"orders_order__shop_id_fkey\" {:columns [\"order__shop_id\"] :foreign-table \"shops\"
                                                              :foreign-columns [\"shop__id\"] :on-delete :refuse
                                                              :on-update :refuse :checked-at :statement
                                                              :match :simple :validated? true}}
                 :uniques      {\"orders_order__id_order__shop_id_key\" {:columns [\"order__id\" \"order__shop_id\"]}}
                 :checks       {\"orders_order__status_enum\" {:condition \"(order__status = ANY (ARRAY['draft'::text, 'done'::text]))\"
                                                           :validated? true}}
                 :indexes      {\"orders_order__shop_id_idx\" {:columns [\"order__shop_id\"] :unique? false
                                                           :body \"USING btree (order__shop_id)\" :valid? true}}}}

   A check's condition and an index's body are as Postgres prints them, so
   the map's, printed by Postgres too (cellar.tables.probe), compare with
   them exactly. Read: unique keys, every check (those whose names end in
   _not_null, cellar's step to a required column, apart), indexes that are
   neither unique nor a primary key, row security and policies, and
   triggers (but Postgres's own). A table outside the public folder is
   named folder.table."
  (:require
   [clojure.string :as str]
   [cellar.queries.postgres :as sql]))

(defn- make-query [file-name]
  (sql/make-query-from-sql-file (str "cellar/tables/" file-name)))

(def ^:private find-tables      (make-query "find_tables.sql"))
(def ^:private find-columns     (make-query "find_columns.sql"))
(def ^:private find-constraints (make-query "find_constraints.sql"))
(def ^:private find-indexes     (make-query "find_indexes.sql"))
(def ^:private find-row-security (make-query "find_row_security.sql"))
(def ^:private find-policies    (make-query "find_policies.sql"))
(def ^:private find-triggers    (make-query "find_triggers.sql"))

(defn- add-gates [tables params pool]
  (as-> tables tables
    (reduce (fn [tables {:keys [table-name row-security forced-row-security]}]
              (if (contains? tables table-name)
                (assoc-in tables [table-name :gated?] (boolean (and row-security forced-row-security)))
                tables))
            tables (sql/find-rows pool find-row-security params))
    (reduce (fn [tables {:keys [table-name policy-name]}]
              (if (contains? tables table-name)
                (assoc-in tables [table-name :policies policy-name] true)
                tables))
            tables (sql/find-rows pool find-policies params))))

(defn- add-triggers [tables params pool]
  (reduce (fn [tables {:keys [table-name trigger-name]}]
            (if (contains? tables table-name)
              (assoc-in tables [table-name :triggers trigger-name] true)
              tables))
          tables (sql/find-rows pool find-triggers params)))

(def ^:private sql-name
  ;; a name as Postgres prints it: plain, or in double quotes
  "(?:\"(?:[^\"]|\"\")*\"|[^\\s\"])+")

(def ^:private index-definition
  (re-pattern (str "(?s)^CREATE (?:UNIQUE )?INDEX " sql-name " ON (?:ONLY )?" sql-name " (USING .*)$")))

(defn find-body-of-index
  "What follows the table in an index's definition as pg_get_indexdef
   prints it: \"USING btree (order__shop_id) WHERE (…)\". Its name and its
   table are left out, so two indexes on two tables compare."
  [definition]
  (or (second (re-find index-definition definition))
      (throw (ex-info (str "An index definition cellar can't read: " definition)
                      {:type :cellar/unread-index :definition definition}))))

(def ^:private reference-actions
  "pg_constraint's confdeltype and confupdtype: no action is cellar's
   :refuse; set default cellar never makes, so a map never says it and it
   differs."
  {"a" :refuse "c" :cascade "r" :restrict "n" :set-null "d" :set-default})

(def ^:private reference-matches
  "pg_constraint's confmatchtype: cellar makes MATCH SIMPLE."
  {"s" :simple "f" :full "p" :partial})

(defn- find-checked-at
  "When a reference is checked: :statement (not deferrable), :commit
   (deferrable, initially deferred), or :deferrable-immediate, which no map
   says."
  [deferrable deferred]
  (cond (not deferrable) :statement
        deferred         :commit
        :else            :deferrable-immediate))

(defn- add-constraint [tables {:keys [table-name constraint-name constraint-type validated condition on-update on-delete
                                      deferrable deferred match column-names foreign-table foreign-column-names]}]
  (case constraint-type
    "p" (assoc-in tables [table-name :primary-key] column-names)
    "f" (assoc-in tables [table-name :foreign-keys constraint-name]
                  {:columns         (vec column-names)
                   :foreign-table   foreign-table
                   :foreign-columns (vec foreign-column-names)
                   :on-delete       (get reference-actions on-delete)
                   :on-update       (get reference-actions on-update)
                   :checked-at      (find-checked-at deferrable deferred)
                   :match           (get reference-matches match)
                   :validated?      validated})
    "u" (assoc-in tables [table-name :uniques constraint-name] {:columns (vec column-names)})
    "c" (if (str/ends-with? constraint-name "_not_null")
          (assoc-in tables [table-name :not-null-checks constraint-name]
                    {:column (first column-names) :validated? validated})
          (assoc-in tables [table-name :checks constraint-name]
                    {:condition condition :validated? validated}))
    tables))

(defn read-catalog
  "The tables in these folders (Postgres schemas) as Postgres has them."
  [pool folders]
  (let [params       {:folders (str/join "," folders)}
        empty-tables (into {} (map (fn [{:keys [table-name]}]
                                     [table-name {:columns [] :primary-key [] :foreign-keys {} :uniques {}
                                                  :checks {} :not-null-checks {} :indexes {}
                                                  :gated? false :policies {} :triggers {}}]))
                           (sql/find-rows pool find-tables params))
        with-columns (reduce (fn [tables {:keys [table-name column-name column-type not-null default-text]}]
                               (update-in tables [table-name :columns] conj
                                          {:name column-name :type column-type
                                           :not-null? not-null :default default-text}))
                             empty-tables
                             (sql/find-rows pool find-columns params))
        with-constraints (reduce add-constraint with-columns (sql/find-rows pool find-constraints params))]
    (-> (reduce (fn [tables {:keys [table-name index-name valid is-unique is-primary backs-constraint definition column-names]}]
                  (if (or is-unique is-primary backs-constraint)
                    tables
                    (assoc-in tables [table-name :indexes index-name]
                              {:columns (vec column-names) :unique? is-unique :body (find-body-of-index definition) :valid? valid})))
                with-constraints
                (sql/find-rows pool find-indexes params))
        (add-gates params pool)
        (add-triggers params pool))))
