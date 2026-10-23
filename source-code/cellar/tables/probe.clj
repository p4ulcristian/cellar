(ns cellar.tables.probe
  "Postgres says how it prints the map's pieces, so they compare exactly
   with what it has: a check's condition, an index's body, a column's
   default. The map says `order__status IN ('draft', 'done')`; Postgres
   prints `(order__status = ANY (ARRAY['draft'::text, 'done'::text]))`.

   Inside a transaction that is always rolled back, each table the plan
   compares is made again as an empty temporary table from its map (its
   columns, types and defaults), its checks and indexes are made on it,
   and the temporary folder is read with the same catalog queries as the
   real tables:

     BEGIN;
     CREATE TEMP TABLE cellar_probe_0 (order__id uuid DEFAULT gen_random_uuid(), order__status text, …);
     ALTER TABLE cellar_probe_0 ADD CONSTRAINT orders_order__status_enum CHECK (order__status IN ('draft', 'done'));
     CREATE INDEX orders_order__shop_id_idx ON cellar_probe_0 USING btree (order__shop_id);
     -- read back: pg_get_expr, pg_get_indexdef
     ROLLBACK;

   It never touches a real table, function or view, and keeps nothing. Made from the map, not
   copied from the real table (LIKE), so a column a pending change renames
   already has its new name. The role planning needs the TEMP right, and
   the transaction can't be read-only. A piece Postgres refuses is a stop,
   with Postgres's words.

   The declared functions and views are made there too, under temporary
   names, and read back; the declared extensions missing are made first,
   so a piece that needs them prints."
  (:require
   [clojure.string :as str]
   [cellar.queries.postgres :as sql]
   [cellar.tables.catalog :as catalog]
   [next.jdbc :as jdbc])
  (:import
   (java.sql SQLException)))

(defn- run-sql! [on sql-text]
  (sql/run-statement! on (sql/make-query-from-sql-text "cellar probe" sql-text) {}))

(def ^:private find-temporary-folder
  (sql/make-query-from-sql-text "cellar probe folder"
                                "SELECT nspname::text AS folder FROM pg_namespace WHERE oid = pg_my_temp_schema()"))

(defn- try-in-savepoint
  "Runs the statement under a savepoint: nil when Postgres takes it, its
   message when it refuses (and the transaction goes on)."
  [on sql-text]
  (run-sql! on "SAVEPOINT cellar_probe_piece")
  (try
    (run-sql! on sql-text)
    (run-sql! on "RELEASE SAVEPOINT cellar_probe_piece")
    nil
    (catch SQLException e
      (run-sql! on "ROLLBACK TO SAVEPOINT cellar_probe_piece")
      (run-sql! on "RELEASE SAVEPOINT cellar_probe_piece")
      (.getMessage e))))

(defn- call-rolled-back
  "(f transaction) in a transaction that is rolled back whatever f does:
   a new one on a pool, a savepoint inside a transaction."
  [pool-or-transaction f]
  (if (:cellar/connection pool-or-transaction)
    (do (run-sql! pool-or-transaction "SAVEPOINT cellar_probe")
        (try
          (f pool-or-transaction)
          (finally
            (run-sql! pool-or-transaction "ROLLBACK TO SAVEPOINT cellar_probe")
            (run-sql! pool-or-transaction "RELEASE SAVEPOINT cellar_probe"))))
    (sql/call-with-connection
     pool-or-transaction
     (fn [connection]
       (jdbc/with-transaction [transaction connection {:rollback-only true}]
         (f {:cellar/connection transaction :cellar/meter (:cellar/meter pool-or-transaction)}))))))

(defn- write-probe-column [{:keys [name type default-sql]}]
  (str name " " type (when default-sql (str " DEFAULT " default-sql))))

(defn- make-pieces
  "The statements that make a table's checks and indexes on its probe."
  [probe-name {:keys [checks indexes]}]
  (concat
   (for [[n {:keys [sql]}] checks]
     [(str "ALTER TABLE " probe-name " ADD CONSTRAINT " n " CHECK (" sql ")") (str "check " n)])
   (for [[n {:keys [unique? sql-body]}] indexes]
     [(str "CREATE " (when unique? "UNIQUE ") "INDEX " n " ON " probe-name " " sql-body) (str "index " n)])))

(def ^:private find-function-printed
  (sql/make-query-from-sql-file "cellar/tables/find_function_printed.sql"))

(def ^:private find-view-printed
  (sql/make-query-from-sql-file "cellar/tables/find_view_printed.sql"))

(defn- print-functions
  "Each declared function made under a temporary name
   (pg_temp.cellar_probe_function_<n>), as anyone with the TEMP right may,
   and read back: {key {:identity [folder name arguments] :definition}}, by
   its place in the list, its definition without its name
   (catalog/leave-out-function-name). One Postgres refuses is a stop."
  [on functions]
  (reduce (fn [result [i {:keys [key source folder name header rest]}]]
            (let [probe-name (str "cellar_probe_function_" i)
                  refusal    (try-in-savepoint on (str header "pg_temp." probe-name rest))
                  printed    (when-not refusal (sql/find-rows on find-function-printed {:name probe-name}))]
              (cond
                refusal
                (update result :stops conj (str source " can't be made: " refusal))
                (not= 1 (count printed))
                (update result :stops conj (str source " makes " (count printed) " functions, not one"))
                :else
                (assoc-in result [:printed key] {:identity   [folder name (:arguments (first printed))]
                                                  :definition (catalog/leave-out-function-name (:definition (first printed)))}))))
          {:printed {} :stops []}
          (map-indexed vector functions)))

(defn- print-views
  "Each view made under a temporary name, and read back: {view-name
   {:definition :options}}, or :unknown when Postgres refuses it here (it
   reads a column or a view this plan makes first): then it is made
   again, after the tables, as one that changed."
  [on views]
  (into {}
        (map-indexed
         (fn [i {:keys [view-name header rest]}]
           (let [probe-name (str "pg_temp.cellar_probe_view_" i)
                 refusal    (try-in-savepoint on (str header probe-name rest))]
             [view-name (if refusal
                          :unknown
                          (select-keys (sql/find-one-row on find-view-printed {:name probe-name}) [:definition :options]))])))
        views))

(defn- print-tables
  "How Postgres prints the pieces of these tables (desired.clj's form, by
   table name): {:printed {table-name {:defaults {column text} :checks
   {name condition} :indexes {name body}}} :stops [message]}."
  [on desired-tables]
  (let [probes (map-indexed (fn [i [table-name table]] [(str "cellar_probe_" i) table-name table]) desired-tables)
        stops  (vec (for [[probe-name table-name table] probes
                          :let [refusal (try-in-savepoint
                                         on (str "CREATE TEMP TABLE " probe-name " ("
                                                 (str/join ", " (map write-probe-column (:columns table))) ")"))]
                          message (if refusal
                                    [(str table-name "'s columns and defaults can't be made: " refusal)]
                                    (for [[piece-sql what] (make-pieces probe-name table)
                                          :let [refusal (try-in-savepoint on piece-sql)]
                                          :when refusal]
                                      (str table-name "'s " what " can't be made: " refusal)))]
                      message))
        folder (when (seq probes) (:folder (sql/find-one-row on find-temporary-folder {})))
        read   (if folder (catalog/read-catalog on [folder]) {})]
    {:printed (into {}
                    (for [[probe-name table-name] probes
                          :let [probe (get read (str folder "." probe-name))]
                          :when probe]
                      [table-name {:defaults (into {} (map (juxt :name :default)) (:columns probe))
                                   :checks   (update-vals (:checks probe) :condition)
                                   :indexes  (update-vals (:indexes probe) :body)}]))
     :stops   stops}))

(defn print-declared
  "How Postgres prints what the maps and the declared files make, in one
   transaction rolled back: the extensions missing made first (a refusal
   is a stop), the declared functions (print-functions), the
   tables' pieces on empty temporary tables (print-tables), and the views
   under temporary names (print-views). Function bodies aren't checked
   (check_function_bodies off), as when the plan makes them. Returns
   {:printed {:tables … :functions … :views …} :stops [message]}."
  [pool-or-transaction {:keys [tables extensions functions views]}]
  (if (and (empty? tables) (empty? extensions) (empty? functions) (empty? views))
    {:printed {:tables {} :functions {} :views {}} :stops []}
    (call-rolled-back
     pool-or-transaction
     (fn [on]
       (run-sql! on "SET LOCAL check_function_bodies = off")
       (let [extension-stops (vec (for [extension extensions
                                        :let [refusal (try-in-savepoint on (str "CREATE EXTENSION IF NOT EXISTS \"" extension "\""))]
                                        :when refusal]
                                    (str "extension " extension " can't be made: " refusal)))
             functions       (print-functions on functions)
             tables          (print-tables on tables)
             views           (print-views on views)]
         {:printed {:tables (:printed tables) :functions (:printed functions) :views views}
          :stops   (vec (concat extension-stops (:stops functions) (:stops tables)))})))))
