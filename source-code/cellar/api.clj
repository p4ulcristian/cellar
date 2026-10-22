(ns cellar.api
  "Talking to Postgres, and shaping its tables. The api of cellar, in two
   halves.

   QUERIES. Every query is a .sql file (or text a library makes from data):

     (def find-orders-of-shop (database/make-query-from-sql-file \"app/orders/find_orders_of_shop.sql\"))
     (database/find-rows pool find-orders-of-shop {:order/shop-id 7})
     ;=> [{:order/id 42 :order/total 120.50M} …]

   In a .sql file a param is :name and a column entity__field comes back as
   :entity/field, with real types (UUID, BigDecimal, java.time). Every
   function that runs a query takes a pool, or the transaction that
   run-in-transaction! hands its function.

   TABLES. Declared as Clojure maps, and Postgres brought to what they say:

     (def orders
       {:table     :orders
        :columns   [[:order/id         :uuid         {:primary-key? true  :optional? false :unique? false :default :random-uuid :references nil}]
                    [:order/shop-id    :uuid         {:primary-key? false :optional? false :unique? false :default nil          :references :shop/id}]
                    [:order/status     [:enum \"draft\" \"done\"] {:primary-key? false :optional? false :unique? false :default \"draft\" :references nil}]
                    [:order/note       :string       {:primary-key? false :optional? true  :unique? false :default nil          :references nil}]
                    [:order/created-at :time/instant {:primary-key? false :optional? false :unique? false :default :now         :references nil}]]
        :indexes   [[:order/shop-id :order/created-at]]
        :gates     nil
        :on-change nil})

     (database/migrate! pool [shops orders] changes
                        {:instant-part {:statement-timeout-ms 30000 :lock-timeout-ms 5000}
                         :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}})

   A table map writes out every key cellar reads (:indexes [] for none,
   :gates and :on-change nil for none); it is open, so another library's
   keys ride along. A column is [key type options], its five options every
   one written out; the type is in malli's words, as wire
   (github.com/p4ulcristian/wire) reads them (:uuid :int :decimal :double
   :string :boolean :time/instant :time/local-date [:enum …]); it is NOT
   NULL unless :optional? is true; :unique? true makes a unique key;
   :default is nil (none), :random-uuid, :now, a number, true/false or text;
   :references is nil or another table's one-column primary key, which it
   points at. A table key with a
   namespace is in that Postgres schema (folder): :billing/invoices is
   billing.invoices; one without is in public.

   Changes are data, for what a map can't say; each runs once, remembered in
   cellar.changes_done (cellar's own table, in its own folder), and a fresh
   database marks them all done:

     [{:id \"2026-10-23-rename-order-note\" :rename-column [:orders :order/note :order/customer-note]}
      {:id \"2026-10-24-drop-order-fax\"    :drop-column [:orders :order/fax] :why \"no faxes since 2019\"}
      {:id \"2026-10-25-rename-jobs\"       :rename-table [:jobs :coatings]}
      {:id \"2026-10-26-drop-old-things\"   :drop-table :old-things :why \"…\"}
      {:id \"2026-10-27-fill-priority\"     :run-sql-file \"app/changes/fill_priority.sql\"}]

   A plan has two parts: :instant (only Postgres's catalog changes; before
   the app starts) and :slow (reads or writes every row: indexes built
   CONCURRENTLY, constraints validated; after, in the background). A
   difference that could lose data, or that v1 can't make, is a stop.

   A table map with :gates ({:read sql :insert sql :update sql :delete sql}:
   SQL conditions the app, or a library on top, passes in) gets row-level
   security, on and forced, and one policy per operation;
   grant-tables-to-role! lets the app's own role, which owns nothing, use the
   tables through them.

   A table map with :on-change ({:function \"app.tell_change\" :arguments
   [\"order__id\" …]}: the name of a Postgres function the app, or a library
   on top, makes and passes in) gets three statement triggers calling that
   function after each insert, update and delete, the changed rows readable
   in it as cellar_new_rows and cellar_old_rows."
  (:require
   [clojure.string :as str]
   [cellar.queries.listen :as listen]
   [cellar.queries.names :as query-names]
   [cellar.queries.postgres :as sql]
   [cellar.queries.tables-read :as tables-read]
   [cellar.settings :as settings]
   [cellar.tables.catalog :as catalog]
   [cellar.tables.desired :as desired]
   [cellar.tables.names :as names]
   [cellar.tables.plan :as plan]
   [next.jdbc :as jdbc]))

;; -----------------------------------------------------------------------------
;; Queries

(def settings-shape
  "What start-pool! takes, every key required (nil and :none are choices):
   where and who, TLS, the pool's HikariCP settings, PgJDBC's network
   timeouts and the session's limits and time zone. See
   cellar.settings/settings-shape for each key."
  settings/settings-shape)

(defn start-pool!
  "Opens a HikariCP pool of connections to Postgres. Every setting is
   required (settings-shape), checked first: a missing or unknown one
   refuses, named, with every other problem at once.

     {:host \"localhost\" :port 5432 :database \"shop\" :user \"shop\" :password nil
      :tls :off :tls-root-cert nil
      :pool-size 10
      :connection-wait-ms 30000 :connection-check-wait-ms 5000
      :connection-max-lifetime-ms 1800000 :connection-keepalive-every-ms 120000
      :leak-warning-after-ms :none
      :connect-timeout-seconds 10 :read-timeout-seconds :none :tcp-keepalive? false
      :statement-timeout-ms 30000 :lock-timeout-ms :none
      :idle-in-transaction-timeout-ms :none
      :time-zone \"UTC\"}

   Returns the pool."
  [db-settings]
  (sql/start-pool! db-settings))

(defn stop-pool!
  "Closes the pool's connections."
  [pool]
  (sql/stop-pool! pool))

(defn make-query-from-sql-file
  "A query from a .sql file on the classpath, read now: a missing file fails
   when the namespace that defs the query loads."
  [path]
  (sql/make-query-from-sql-file path))

(defn make-query-from-sql-text
  "A query from SQL text made by code, not read from a file (a table's
   statements, made from its map). The name stands where a file's path
   would, in errors."
  [query-name sql-text]
  (sql/make-query-from-sql-text query-name sql-text))

(defn find-rows
  "Every row of a query that returns rows: a vector, empty when none."
  [pool-or-transaction query params]
  (sql/find-rows pool-or-transaction query params))

(defn find-one-row
  "The one row of a query, or nil; a second row throws."
  [pool-or-transaction query params]
  (sql/find-one-row pool-or-transaction query params))

(defn run-statement!
  "Runs a statement for what it changes. Returns how many rows it changed."
  [pool-or-transaction query params]
  (sql/run-statement! pool-or-transaction query params))

(defn run-in-transaction!
  "Runs (f transaction) in one transaction: kept when f returns, undone when
   it throws. Returns what f returns. Inside a transaction, it joins that one.
   Options, every key required: {:read-only? false}; true, Postgres refuses
   every write in it."
  [pool-or-transaction options f]
  (sql/run-in-transaction! pool-or-transaction options f))

;; -----------------------------------------------------------------------------
;; What a question read, and hearing changes

(defn run-and-list-tables-read
  "Runs (f) and returns [what f returned, the tables its queries read]: a
   set of table keys (#{:orders :shops}), or :unknown when Postgres's
   planner couldn't say for one of them (a function that reads tables
   inside it). Each query is planned once, without running it."
  [f]
  (tables-read/run-and-list-tables-read f))

(defn turn-table-name-into-table-key
  "A table's key from its folder and name: \"public\" \"orders\" -> :orders,
   \"billing\" \"invoices\" -> :billing/invoices."
  [folder table-name]
  (query-names/find-key-for-table folder table-name))

(defn start-listening!
  "LISTENs on a Postgres channel, on one connection of the pool held for as
   long as it runs. Options, every key required:

     {:on-notifications       f      ; (f [{:channel :payload} …]) for each batch
      :on-reconnect           f      ; (f) after a break, listening again: what came in between was missed
      :batch-window-ms        50     ; after the first notification, how long more join its batch
      :check-stopped-every-ms 500    ; one wait for a notification, before looking whether it was stopped
      :reconnect-after-ms     1000   ; after a break, the wait before connecting again
      :stop-wait-ms           2000}  ; how long stop-listening! waits for the thread

   Returns the listener."
  [pool channel options]
  (listen/start-listening! pool channel options))

(defn stop-listening!
  "Stops a listener; its connection goes back to the pool. Waits at most
   its :stop-wait-ms; returns whether it had ended."
  [listener]
  (listen/stop-listening! listener))

;; -----------------------------------------------------------------------------
;; Tables


(defn- make-query [file-name]
  (sql/make-query-from-sql-file (str "cellar/tables/" file-name)))

(def ^:private create-changes-done (make-query "create_changes_done.sql"))
(def ^:private find-changes-done   (make-query "find_changes_done.sql"))
(def ^:private insert-change-done  (make-query "insert_change_done.sql"))

(def ^:private set-part-limits     (make-query "set_part_limits.sql"))
(def ^:private reset-part-limits   (make-query "reset_part_limits.sql"))

(defn- set-part-limits! [transaction-or-connection {:keys [statement-timeout-ms lock-timeout-ms]} local?]
  (let [as-text #(str (if (= :none %) 0 %))]
    (sql/find-one-row transaction-or-connection set-part-limits
                      {:statement-timeout (as-text statement-timeout-ms)
                       :lock-timeout      (as-text lock-timeout-ms)
                       :local             local?})))

(def ^:private does-changes-done-exist
  (sql/make-query-from-sql-text "changes_done is there" "SELECT to_regclass('cellar.changes_done') IS NOT NULL AS exists"))

(defn- find-changes-done-if-any
  "The changes done, or none when the table isn't there yet: planning reads
   only, so the app's own role can check a database it can't change."
  [pool]
  (when (:exists (sql/find-one-row pool does-changes-done-exist {}))
    (sql/find-rows pool find-changes-done {})))

(defn- turn-statement-into-query [{:keys [sql why]}]
  (sql/make-query-from-sql-text why sql))

(defn- find-whether-table-has-rows [pool table-name]
  (:has-rows (sql/find-one-row pool (sql/make-query-from-sql-text
                                     (str "rows of " table-name)
                                     (str "SELECT EXISTS (SELECT 1 FROM " table-name ") AS has_rows"))
                               {})))

(defn make-plan-from-tables
  "What would make Postgres match the tables and changes, without running
   anything: {:instant [{:sql :why}] :slow [{:sql :why}] :stops [message]
   :changes-to-mark [id]}."
  [pool tables changes]
  (settings/check-tables tables)
  (let [desired (desired/turn-table-maps-into-desired tables)
        folders (set (map names/find-folder-of-table (keys desired)))]
    (plan/make-plan {:catalog      (catalog/read-catalog pool folders)
                   :desired      desired
                   :changes      changes
                   :changes-done (set (map :change-done/id (find-changes-done-if-any pool)))
                   :has-rows?    (memoize #(find-whether-table-has-rows pool %))})))

(defn run-instant-part!
  "Runs the plan's instant part and marks its changes done, in one
   transaction: all of it, or none. A plan with stops throws, running
   nothing. Options, every key required (the part's own time limits, in
   place of the pool's, for this transaction only):

     {:statement-timeout-ms 30000 :lock-timeout-ms 5000}"
  [pool {:keys [instant stops changes-to-mark]} options]
  (settings/check-part-options options)
  (when (seq stops)
    (throw (ex-info (str "The database can't be brought to the maps:\n  " (str/join "\n  " stops))
                    {:type :cellar/stops :stops stops})))
  (sql/run-in-transaction! pool {:read-only? false}
    (fn [transaction]
      (set-part-limits! transaction options true)
      (sql/run-statement! transaction create-changes-done {})
      (doseq [statement instant]
        (sql/run-statement! transaction (turn-statement-into-query statement) {}))
      (doseq [id changes-to-mark]
        (sql/run-statement! transaction insert-change-done {:change-done/id id})))))

(defn run-slow-part!
  "Runs the plan's slow part, one statement at a time, outside a
   transaction (CONCURRENTLY needs that), on one connection. A failure
   throws; the next plan finds what is left and plans it again. Options,
   every key required (the part's own time limits, in place of the pool's;
   the connection gets the pool's back after):

     {:statement-timeout-ms :none :lock-timeout-ms 5000}"
  [pool {:keys [slow]} options]
  (settings/check-part-options options)
  (with-open [connection (jdbc/get-connection (:cellar/datasource pool))]
    (let [on-connection {:cellar/connection connection}]
      (try
        (set-part-limits! on-connection options false)
        (doseq [statement slow]
          (sql/run-statement! on-connection (turn-statement-into-query statement) {}))
        (finally
          (sql/run-statement! on-connection reset-part-limits {}))))))

(defn- print-plan [{:keys [instant slow]}]
  (doseq [[part statements] [["now" instant] ["in the background" slow]]
          {:keys [sql why]} statements]
    (println (str "cellar (" part ", " why "):\n  " (str/replace sql "\n" "\n  ")))))

(defn migrate!
  "Brings Postgres to the tables, for development: plans, throws on a stop
   (nothing runs), prints the plan, runs the instant part now and the slow
   part on a background thread, each with its options (every key required):

     {:instant-part {:statement-timeout-ms 30000 :lock-timeout-ms 5000}
      :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}}

   Returns the plan with :slow-part, a future of the slow part (deref it to
   wait)."
  [pool tables changes options]
  (settings/check-migrate-options options)
  (let [plan (make-plan-from-tables pool tables changes)]
    (run-instant-part! pool plan (:instant-part options))
    (print-plan plan)
    (assoc plan :slow-part (future (run-slow-part! pool plan (:slow-part options))))))

(defn does-database-match-tables?
  "Whether Postgres has everything the tables and changes need to run: no
   instant part left, no stop, no change waiting. A slow part still to run
   is allowed."
  [pool tables changes]
  (let [{:keys [instant stops changes-to-mark]} (make-plan-from-tables pool tables changes)]
    (and (empty? instant) (empty? stops) (empty? changes-to-mark))))

(defn turn-table-into-row-shape
  "The malli shape of a table's rows, for wire: an optional column
   may be missing or nil."
  [table]
  (settings/check-tables [table])
  (desired/turn-table-into-row-shape table))

(defn turn-key-into-column-name
  "A column key's Postgres name: :order/shop-id -> \"order__shop_id\"."
  [k]
  (names/turn-key-into-column-name k))

(defn turn-table-key-into-table-name
  "A table key's Postgres name: :orders -> \"orders\", :billing/invoices -> \"billing.invoices\"."
  [k]
  (names/turn-table-key-into-table-name k))

(defn grant-tables-to-role!
  "Lets the app's own database role use the tables of these maps: their
   folders, their rows (select, insert, update, delete), and the functions
   in them. The role owns nothing, so the gates hold for it. It may also
   read cellar.changes_done, so it can check the database with
   does-database-match-tables?. Run by migrate as the owner; safe to run
   again."
  [pool tables role]
  (settings/check-tables tables)
  (sql/run-statement! pool create-changes-done {})
  (sql/run-statement! pool (sql/make-query-from-sql-text
                            (str "grant cellar.changes_done to " role)
                            (str "GRANT USAGE ON SCHEMA cellar TO " role "; "
                                 "GRANT SELECT ON cellar.changes_done TO " role))
                      {})
  (doseq [folder (set (map (comp names/find-folder-of-table names/turn-table-key-into-table-name :table) tables))]
    (sql/run-statement! pool (sql/make-query-from-sql-text
                              (str "grant " folder " to " role)
                              (str "GRANT USAGE ON SCHEMA " folder " TO " role "; "
                                   "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " folder " TO " role "; "
                                   "GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA " folder " TO " role))
                        {})))
