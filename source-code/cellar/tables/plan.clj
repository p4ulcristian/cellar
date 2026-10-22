(ns cellar.tables.plan
  "The plan: what to run so the database matches the maps.

   First the change files not yet run are applied, on paper, to what
   Postgres has (a rename renames, a drop drops). Then that is compared with
   the maps. Every difference becomes statements, each in one of two parts:

     :instant  changes only Postgres's catalog; runs before the app starts
     :slow     reads or writes every row; runs after, in the background

   A difference that could lose data or that v1 can't make is a stop: a
   message, and nothing runs."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [cellar.tables.names :as names]
   [cellar.tables.types :as types]
   [cellar.queries.postgres :as sql]))

(defn- statement [sql-text why] {:sql sql-text :why why})

(defn- add-instant [state sql-text why] (update state :instant conj (statement sql-text why)))
(defn- add-slow [state sql-text why] (update state :slow conj (statement sql-text why)))
(defn- add-stop [state message] (update state :stops conj message))

;; -----------------------------------------------------------------------------
;; Change files, applied on paper

(defn- rename-derived-names
  "After a table or column rename, the constraints and indexes schema named
   after the old names get the new ones (Postgres keeps the old names)."
  [state old-table new-table column-renames]
  (let [old-column (fn [c] (get (set/map-invert column-renames) c c))
        table      (get-in state [:catalog new-table])
        renames    (concat
                    (for [[n {:keys [column]}] (:foreign-keys table)
                          :when (= n (names/make-foreign-key-name old-table (old-column column)))]
                      [:foreign-keys n (names/make-foreign-key-name new-table column) :constraint])
                    (for [[n {:keys [column]}] (:uniques table)
                          :when (= n (names/make-unique-name old-table (old-column column)))]
                      [:uniques n (names/make-unique-name new-table column) :constraint])
                    (for [[n {:keys [column]}] (:checks table)
                          :when (= n (names/make-enum-check-name old-table (old-column column)))]
                      [:checks n (names/make-enum-check-name new-table column) :constraint])
                    (for [[n {:keys [column]}] (:not-null-checks table)
                          :when (= n (names/make-not-null-check-name old-table (old-column column)))]
                      [:not-null-checks n (names/make-not-null-check-name new-table column) :constraint])
                    (for [[n {:keys [columns]}] (:indexes table)
                          :when (= n (names/make-index-name old-table (map old-column columns)))]
                      [:indexes n (names/make-index-name new-table columns) :index]))]
    (reduce (fn [state [kind old-name new-name what]]
              (if (= old-name new-name)
                state
                (-> state
                    (update-in [:catalog new-table kind] set/rename-keys {old-name new-name})
                    (add-instant (if (= :index what)
                                   (str "ALTER INDEX " (names/qualify-index-name new-table old-name) " RENAME TO " new-name)
                                   (str "ALTER TABLE " new-table " RENAME CONSTRAINT " old-name " TO " new-name))
                                 (str "keep the name of " old-name " after a rename")))))
            state
            renames)))

(defn- point-references-at [catalog old-table new-table column-renames]
  (update-vals catalog
               (fn [table]
                 (update table :foreign-keys update-vals
                         (fn [{:keys [foreign-table foreign-column] :as fk}]
                           (if (= foreign-table old-table)
                             (assoc fk :foreign-table new-table
                                    :foreign-column (get column-renames foreign-column foreign-column))
                             fk))))))

(defn- rename-column-in-table [table from to]
  (let [rename #(if (= % from) to %)]
    (-> table
        (update :columns (partial mapv #(update % :name rename)))
        (update :primary-key (partial mapv rename))
        (update :foreign-keys update-vals #(update % :column rename))
        (update :uniques update-vals #(update % :column rename))
        (update :checks update-vals #(update % :column rename))
        (update :not-null-checks update-vals #(update % :column rename))
        (update :indexes update-vals #(update % :columns (partial mapv rename))))))

(defn- find-references-to [catalog table-name column-name]
  (for [[from-table {:keys [foreign-keys]}] catalog
        [_ {:keys [foreign-table foreign-column]}] foreign-keys
        :when (and (= foreign-table table-name) (or (nil? column-name) (= foreign-column column-name)))
        :when (not= from-table table-name)]
    from-table))

(defn- apply-change
  [state {:keys [id rename-table rename-column drop-column drop-table run-sql-file why] :as change}]
  (let [catalog (:catalog state)
        stop    #(add-stop state (str "change " id ": " %))]
    (cond
      rename-table
      (let [[from to] (map names/turn-table-key-into-table-name rename-table)]
        (cond
          (not (contains? catalog from)) (stop (str "there is no table " from " to rename"))
          (contains? catalog to)         (stop (str "there is already a table " to))
          :else (-> state
                    (add-instant (str "ALTER TABLE " from " RENAME TO " (names/find-table-part to)) (str "change " id))
                    (assoc :catalog (-> catalog
                                        (set/rename-keys {from to})
                                        (point-references-at from to {})))
                    (rename-derived-names from to {}))))

      rename-column
      (let [[table-key from-key to-key] rename-column
            table-name (names/turn-table-key-into-table-name table-key)
            from       (names/turn-key-into-column-name from-key)
            to         (names/turn-key-into-column-name to-key)
            columns    (set (map :name (get-in catalog [table-name :columns])))]
        (cond
          (not (contains? columns from)) (stop (str "there is no column " table-name "." from " to rename"))
          (contains? columns to)         (stop (str "there is already a column " table-name "." to))
          :else (-> state
                    (add-instant (str "ALTER TABLE " table-name " RENAME COLUMN " from " TO " to) (str "change " id))
                    (update-in [:catalog table-name] rename-column-in-table from to)
                    (update :catalog point-references-at table-name table-name {from to})
                    (rename-derived-names table-name table-name {from to}))))

      drop-column
      (let [[table-key column-key] drop-column
            table-name (names/turn-table-key-into-table-name table-key)
            column     (names/turn-key-into-column-name column-key)]
        (cond
          (not why) (stop "a drop needs a :why")
          (not (some #{column} (map :name (get-in catalog [table-name :columns]))))
          (stop (str "there is no column " table-name "." column " to drop"))
          (seq (find-references-to catalog table-name column))
          (stop (str table-name "." column " is referenced by " (str/join ", " (find-references-to catalog table-name column))
                     "; remove those references from their maps first"))
          :else (-> state
                    (add-instant (str "ALTER TABLE " table-name " DROP COLUMN " column) (str "change " id ": " why))
                    (update-in [:catalog table-name]
                               (fn [table]
                                 (let [uses? #(= column %)]
                                   (-> table
                                       (update :columns (partial filterv #(not (uses? (:name %)))))
                                       (update :foreign-keys #(into {} (remove (comp uses? :column val)) %))
                                       (update :uniques #(into {} (remove (comp uses? :column val)) %))
                                       (update :checks #(into {} (remove (comp uses? :column val)) %))
                                       (update :not-null-checks #(into {} (remove (comp uses? :column val)) %))
                                       (update :indexes #(into {} (remove (fn [[_ {:keys [columns]}]] (some uses? columns))) %)))))))))

      drop-table
      (let [table-name (names/turn-table-key-into-table-name drop-table)]
        (cond
          (not why) (stop "a drop needs a :why")
          (not (contains? catalog table-name)) (stop (str "there is no table " table-name " to drop"))
          (seq (find-references-to catalog table-name nil))
          (stop (str table-name " is referenced by " (str/join ", " (find-references-to catalog table-name nil))
                     "; remove those references from their maps first"))
          :else (-> state
                    (add-instant (str "DROP TABLE " table-name) (str "change " id ": " why))
                    (update :catalog dissoc table-name))))

      run-sql-file
      (let [query (sql/make-query-from-sql-file run-sql-file)]
        (if (seq (:param-names query))
          (stop (str run-sql-file " has params; a change file's SQL runs without any"))
          (add-instant state (:text-with-question-marks query) (str "change " id ": " run-sql-file))))

      :else (stop (str "not a change schema knows: " (pr-str (dissoc change :id)))))))

;; -----------------------------------------------------------------------------
;; Comparing a table

(defn- write-column [{:keys [name type not-null? default-sql]}]
  (str name " " type (when not-null? " NOT NULL") (when default-sql (str " DEFAULT " default-sql))))

(defn- write-enum-check [table-name check-name {:keys [column values]}]
  (str "ALTER TABLE " table-name " ADD CONSTRAINT " check-name
       " CHECK (" column " IN (" (str/join ", " (map types/quote-text values)) "))"))

(defn- write-foreign-key [table-name fk-name {:keys [column foreign-table foreign-column]}]
  (str "ALTER TABLE " table-name " ADD CONSTRAINT " fk-name
       " FOREIGN KEY (" column ") REFERENCES " foreign-table " (" foreign-column ")"))

(defn- write-table-parts [{:keys [columns primary-key uniques]}]
  (concat (map write-column columns)
          (when (seq primary-key) [(str "PRIMARY KEY (" (str/join ", " primary-key) ")")])
          (for [[n {:keys [column]}] uniques] (str "CONSTRAINT " n " UNIQUE (" column ")"))))

(defn- plan-new-table
  "A table not in Postgres: made whole. It has no rows, so its keys and
   indexes are made at once, in the instant part."
  [state table-name {:keys [checks indexes] :as table}]
  (as-> state state
    (add-instant state
                 (str "CREATE TABLE " table-name " (\n  " (str/join ",\n  " (write-table-parts table)) "\n)")
                 (str "missing table " table-name))
    (reduce (fn [state [n check]] (add-instant state (write-enum-check table-name n check) (str "enum of new table " table-name)))
            state checks)
    (reduce (fn [state [n {:keys [columns]}]]
              (add-instant state (str "CREATE INDEX " n " ON " table-name " (" (str/join ", " columns) ")")
                           (str "index of new table " table-name)))
            state indexes)))

(defn- plan-foreign-keys-of-new-table [state table-name {:keys [foreign-keys]}]
  (reduce (fn [state [n fk]] (add-instant state (write-foreign-key table-name n fk) (str "reference of new table " table-name)))
          state foreign-keys))

(defn- plan-column [state table-name has-rows? existing {:keys [name type not-null? default default-sql volatile-default?] :as column}]
  (let [where (str table-name "." name)]
    (if-not existing
      (cond
        (and not-null? (not default-sql) (has-rows? table-name))
        (add-stop state (str where " is required but " table-name " has rows: give it a :default, "
                             "or add it {:optional? true} and fill it with a change file"))
        (and volatile-default? (has-rows? table-name))
        (add-stop state (str where "'s default is computed per row, so adding it would rewrite " table-name
                             ": add it {:optional? true} and fill it with a change file"))
        :else
        (add-instant state (str "ALTER TABLE " table-name " ADD COLUMN " (write-column column)) (str "missing column " where)))
      (cond-> state
        (not= type (:type existing))
        (add-stop (str where " is " (:type existing) " in Postgres but " type " in its map; type changes aren't in v1"))

        (and (not not-null?) (:not-null? existing))
        (add-instant (str "ALTER TABLE " table-name " ALTER COLUMN " name " DROP NOT NULL") (str where " became optional"))

        (not= default (:default existing))
        (add-instant (if default-sql
                       (str "ALTER TABLE " table-name " ALTER COLUMN " name " SET DEFAULT " default-sql)
                       (str "ALTER TABLE " table-name " ALTER COLUMN " name " DROP DEFAULT"))
                     (str where "'s default changed"))))))

(defn- plan-required
  "A column that became required: proven on old rows without blocking
   writes, then made NOT NULL. A check NOT VALID now; VALIDATE, SET NOT NULL
   and dropping the check in the slow part."
  [state table-name existing-table desired-table]
  (reduce
   (fn [state {:keys [name not-null?]}]
     (let [existing   (some #(when (= name (:name %)) %) (:columns existing-table))
           check-name (names/make-not-null-check-name table-name name)
           check      (get-in existing-table [:not-null-checks check-name])
           finish     (fn [state]
                        (-> state
                            (add-slow (str "ALTER TABLE " table-name " ALTER COLUMN " name " SET NOT NULL")
                                      (str table-name "." name " became required"))
                            (add-slow (str "ALTER TABLE " table-name " DROP CONSTRAINT " check-name)
                                      (str table-name "." name " is NOT NULL now"))))]
       (cond
         (not (and existing not-null? (not (:not-null? existing)))) state
         (nil? check)
         (-> state
             (add-instant (str "ALTER TABLE " table-name " ADD CONSTRAINT " check-name
                               " CHECK (" name " IS NOT NULL) NOT VALID")
                          (str table-name "." name " became required"))
             (add-slow (str "ALTER TABLE " table-name " VALIDATE CONSTRAINT " check-name)
                       (str "prove " table-name "." name " on old rows"))
             finish)
         (not (:validated? check))
         (-> state
             (add-slow (str "ALTER TABLE " table-name " VALIDATE CONSTRAINT " check-name)
                       (str "prove " table-name "." name " on old rows"))
             finish)
         :else (finish state))))
   state
   (:columns desired-table)))

(defn- plan-uniques
  "A unique key on a table that may have rows: its index built CONCURRENTLY,
   then the key made from it, in the slow part. One in no map is dropped."
  [state table-name existing desired]
  (as-> state state
    (reduce (fn [state [n {:keys [column]}]]
              (if (contains? (:uniques existing) n)
                state
                (-> state
                    (add-slow (str "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS " n " ON " table-name " (" column ")")
                              (str "missing unique key " n))
                    (add-slow (str "ALTER TABLE " table-name " ADD CONSTRAINT " n " UNIQUE USING INDEX " n)
                              (str "unique key " n " from its index")))))
            state (:uniques desired))
    (reduce (fn [state [n _]]
              (if (contains? (:uniques desired) n)
                state
                (add-instant state (str "ALTER TABLE " table-name " DROP CONSTRAINT " n) (str "unique key " n " is in no map"))))
            state (:uniques existing))))

(defn- plan-gates
  "Row-level security on and forced when the map has :gates, off when it
   hasn't; the policies made and dropped by name. All instant."
  [state table-name existing desired]
  (as-> state state
    (cond
      (and (:gated? desired) (not (:gated? existing)))
      (add-instant state (str "ALTER TABLE " table-name " ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY")
                   (str table-name " gets its gates"))
      (and (not (:gated? desired)) (:gated? existing))
      (add-instant state (str "ALTER TABLE " table-name " DISABLE ROW LEVEL SECURITY, NO FORCE ROW LEVEL SECURITY")
                   (str table-name " has no gates in its map"))
      :else state)
    (reduce (fn [state [policy-name _]]
              (if (contains? (:policies desired) policy-name)
                state
                (add-instant state (str "DROP POLICY " policy-name " ON " table-name) (str "policy " policy-name " is in no map"))))
            state (:policies existing))
    (reduce (fn [state [policy-name policy-sql]]
              (if (contains? (:policies existing) policy-name)
                state
                (add-instant state policy-sql (str "missing policy " policy-name))))
            state (:policies desired))))

(defn- plan-triggers
  "The change triggers made and dropped by name. All instant."
  [state table-name existing desired]
  (as-> state state
    (reduce (fn [state [trigger-name _]]
              (if (contains? (:triggers desired) trigger-name)
                state
                (add-instant state (str "DROP TRIGGER " trigger-name " ON " table-name) (str "trigger " trigger-name " is in no map"))))
            state (:triggers existing))
    (reduce (fn [state [trigger-name trigger-sql]]
              (if (contains? (:triggers existing) trigger-name)
                state
                (add-instant state trigger-sql (str "missing trigger " trigger-name))))
            state (:triggers desired))))

(defn- plan-existing-table [state has-rows? table-name existing desired]
  (let [existing-columns (into {} (map (juxt :name identity)) (:columns existing))
        desired-names    (set (map :name (:columns desired)))]
    (as-> state state
      (reduce #(plan-column %1 table-name has-rows? (get existing-columns (:name %2)) %2) state (:columns desired))
      (reduce (fn [state {:keys [name]}]
                (if (contains? desired-names name)
                  state
                  (add-stop state (str table-name "." name " is in Postgres but in no map: add a change file, "
                                       "{:rename-column [:" table-name " … …]} or {:drop-column [:" table-name " …] :why \"…\"}"))))
              state (:columns existing))
      (if (= (:primary-key existing) (:primary-key desired))
        state
        (add-stop state (str table-name "'s primary key differs from its map; changing it isn't in v1")))
      (plan-required state table-name existing desired)
      (plan-uniques state table-name existing desired)
      (plan-gates state table-name existing desired)
      (plan-triggers state table-name existing desired)
      ;; references: NOT VALID now, VALIDATE in the slow part
      (reduce (fn [state [n fk]]
                (let [found (get-in existing [:foreign-keys n])]
                  (cond
                    (nil? found)
                    (-> state
                        (add-instant (str (write-foreign-key table-name n fk) " NOT VALID") (str "missing reference " n))
                        (add-slow (str "ALTER TABLE " table-name " VALIDATE CONSTRAINT " n) (str "prove " n " on old rows")))
                    (not (:validated? found))
                    (add-slow state (str "ALTER TABLE " table-name " VALIDATE CONSTRAINT " n) (str "prove " n " on old rows"))
                    :else state)))
              state (:foreign-keys desired))
      (reduce (fn [state [n _]]
                (if (contains? (:foreign-keys desired) n)
                  state
                  (add-instant state (str "ALTER TABLE " table-name " DROP CONSTRAINT " n) (str "reference " n " is in no map"))))
              state (:foreign-keys existing))
      ;; enums
      (reduce (fn [state [n check]]
                (let [found (get-in existing [:checks n])]
                  (cond
                    (nil? found)
                    (-> state
                        (add-instant (str (write-enum-check table-name n check) " NOT VALID") (str "missing enum " n))
                        (add-slow (str "ALTER TABLE " table-name " VALIDATE CONSTRAINT " n) (str "prove " n " on old rows")))
                    (not= (:values check) (:values found))
                    (-> state
                        (add-instant (str "ALTER TABLE " table-name " DROP CONSTRAINT " n) (str "enum " n " changed"))
                        (add-instant (str (write-enum-check table-name n check) " NOT VALID") (str "enum " n " changed"))
                        (add-slow (str "ALTER TABLE " table-name " VALIDATE CONSTRAINT " n) (str "prove " n " on old rows")))
                    (not (:validated? found))
                    (add-slow state (str "ALTER TABLE " table-name " VALIDATE CONSTRAINT " n) (str "prove " n " on old rows"))
                    :else state)))
              state (:checks desired))
      (reduce (fn [state [n _]]
                (if (contains? (:checks desired) n)
                  state
                  (add-instant state (str "ALTER TABLE " table-name " DROP CONSTRAINT " n) (str "enum " n " is in no map"))))
              state (:checks existing))
      ;; indexes: built and dropped CONCURRENTLY, in the slow part
      (reduce (fn [state [n {:keys [columns]}]]
                (let [found (get-in existing [:indexes n])]
                  (cond
                    (nil? found)
                    (add-slow state (str "CREATE INDEX CONCURRENTLY IF NOT EXISTS " n " ON " table-name
                                         " (" (str/join ", " columns) ")")
                              (str "missing index " n))
                    (not (:valid? found))
                    (-> state
                        (add-slow (str "DROP INDEX CONCURRENTLY IF EXISTS " (names/qualify-index-name table-name n)) (str "index " n " was left invalid"))
                        (add-slow (str "CREATE INDEX CONCURRENTLY IF NOT EXISTS " n " ON " table-name
                                       " (" (str/join ", " columns) ")")
                                  (str "rebuild index " n)))
                    :else state)))
              state (:indexes desired))
      (reduce (fn [state [n _]]
                (if (contains? (:indexes desired) n)
                  state
                  (add-slow state (str "DROP INDEX CONCURRENTLY IF EXISTS " (names/qualify-index-name table-name n)) (str "index " n " is in no map"))))
              state (:indexes existing)))))

(defn make-plan
  "The plan from what Postgres has (catalog), what the maps say (desired),
   the change files and which have run, and has-rows? (a table name -> whether
   it has rows, asked only when it matters)."
  [{:keys [catalog desired changes changes-done has-rows?]}]
  (let [fresh?   (and (empty? changes-done) (not-any? #(contains? catalog %) (keys desired)))
        pending  (remove #(contains? changes-done (:id %)) changes)
        start    {:catalog catalog :instant [] :slow [] :stops []
                  :changes-to-mark (mapv :id pending)}
        state    (if fresh? start (reduce apply-change start pending))
        catalog  (:catalog state)
        new      (remove #(contains? catalog %) (keys desired))
        folders  (->> new (map names/find-folder-of-table) (remove #{"public"}) distinct)
        state    (as-> state state
                   (reduce #(add-instant %1 (str "CREATE SCHEMA IF NOT EXISTS " %2) (str "folder " %2 " for its tables")) state folders)
                   (reduce #(plan-new-table %1 %2 (get desired %2)) state new)
                   (reduce #(plan-foreign-keys-of-new-table %1 %2 (get desired %2)) state new)
                   (reduce #(plan-gates %1 %2 {:gated? false :policies {}} (get desired %2)) state new)
                   (reduce #(plan-triggers %1 %2 {:triggers {}} (get desired %2)) state new)
                   (reduce (fn [state table-name]
                             (plan-existing-table state has-rows? table-name (get catalog table-name) (get desired table-name)))
                           state
                           (filter #(contains? catalog %) (keys desired)))
                   (reduce (fn [state table-name]
                             (add-stop state (str "table " table-name " is in Postgres but in no map: "
                                                  "add a change file, {:rename-table [:" table-name " …]} "
                                                  "or {:drop-table :" table-name " :why \"…\"}")))
                           state
                           (remove #(contains? desired %) (keys catalog))))]
    (select-keys state [:instant :slow :stops :changes-to-mark])))
