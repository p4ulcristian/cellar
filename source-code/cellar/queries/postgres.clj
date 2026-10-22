(ns cellar.queries.postgres
  "Postgres through a pool, with every query in a .sql file: the queries
   half of cellar, which its api hands on.

     (def find-orders-of-shop (sql/make-query-from-sql-file \"app/orders/find_orders_of_shop.sql\"))
     (sql/find-rows pool find-orders-of-shop {:order/shop-id 7})
     ;=> [{:order/id 42 :order/total 120.50M} …]

   In a .sql file a param is :name (:order/shop-id for a field of the data,
   :limit for a control of the query) and a column is named entity__field,
   which comes back as :entity/field. Values come back as real types: UUID,
   BigDecimal, java.time.

   Every function that runs a query takes a pool, or the transaction that
   run-in-transaction! hands its function: the same calls work in both."
  (:require
   [clojure.java.io :as io]
   [cellar.queries.params :as params]
   [cellar.queries.pool-lines :as pool-lines]
   [cellar.queries.rows :as rows]
   [cellar.queries.tables-read :as tables-read]
   [cellar.settings :as settings]
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as connection])
  (:import
   (com.zaxxer.hikari HikariConfig HikariDataSource)
   (java.sql Connection PreparedStatement)))

;; -----------------------------------------------------------------------------
;; The pool

(defn- turn-none-into-zero
  "A limit as Postgres, HikariCP and PgJDBC take it: :none is 0, no limit."
  [value]
  (if (= :none value) 0 value))

(defn- tls-properties
  ;; :verified is verify-full: encrypted, and the server's certificate must
  ;; lead to a trusted one (the Java runtime's, or :tls-root-cert) and name
  ;; :host; a server without TLS is refused, never used plain
  [{:keys [tls tls-root-cert]}]
  (case tls
    :off      {"sslmode" "disable"}
    :verified (if tls-root-cert
                {"sslmode"     "verify-full"
                 "sslfactory"  "org.postgresql.ssl.LibPQFactory"
                 "sslrootcert" tls-root-cert}
                {"sslmode"     "verify-full"
                 "sslfactory"  "org.postgresql.ssl.DefaultJavaSSLFactory"})))

(defn- make-session-options
  "The session's limits, as PgJDBC's options property hands them to
   Postgres when a connection opens: they hold from its first statement."
  [{:keys [statement-timeout-ms lock-timeout-ms idle-in-transaction-timeout-ms]}]
  (str "-c statement_timeout=" (turn-none-into-zero statement-timeout-ms)
       " -c lock_timeout=" (turn-none-into-zero lock-timeout-ms)
       " -c idle_in_transaction_session_timeout=" (turn-none-into-zero idle-in-transaction-timeout-ms)))

(defn start-pool!
  "Opens a HikariCP pool of connections to Postgres, as the settings say
   (cellar.settings/settings-shape, every key required, checked first).
   Options, every key required (cellar.settings/pool-options-shape): :log,
   the app's log, which HikariCP's and PgJDBC's own lines about this pool
   are written to (:cellar/pool-said, cellar.queries.pool-lines). Returns
   the pool."
  [db-settings options]
  (settings/check-settings db-settings)
  (settings/check-pool-options options)
  (let [{:keys [host port database user password tls-root-cert pool-size
                connection-wait-ms connection-check-wait-ms connection-max-lifetime-ms
                connection-keepalive-every-ms leak-warning-after-ms
                connect-timeout-seconds read-timeout-seconds tcp-keepalive? time-zone]} db-settings
        pool-name (pool-lines/make-pool-name database)]
    (when (and tls-root-cert (not (.canRead (io/file tls-root-cert))))
      (throw (ex-info (str ":tls-root-cert names no file that can be read: " tls-root-cert)
                      {:type :cellar/bad-setting :setting :tls-root-cert :value tls-root-cert})))
    (let [config (doto (HikariConfig.)
                   (.setJdbcUrl (connection/jdbc-url {:dbtype "postgresql" :host host :port port :dbname database}))
                   (.setUsername user)
                   ;; unique in the JVM: HikariCP's lines and threads carry it,
                   ;; and it finds their log
                   (.setPoolName pool-name)
                   ;; the settings
                   (.setMaximumPoolSize pool-size)
                   (.setConnectionTimeout connection-wait-ms)
                   (.setValidationTimeout connection-check-wait-ms)
                   (.setMaxLifetime (turn-none-into-zero connection-max-lifetime-ms))
                   (.setKeepaliveTime (turn-none-into-zero connection-keepalive-every-ms))
                   (.setLeakDetectionThreshold (turn-none-into-zero leak-warning-after-ms))
                   (.addDataSourceProperty "connectTimeout" (str connect-timeout-seconds))
                   (.addDataSourceProperty "socketTimeout" (str (turn-none-into-zero read-timeout-seconds)))
                   (.addDataSourceProperty "tcpKeepAlive" (str tcp-keepalive?))
                   (.addDataSourceProperty "options" (make-session-options db-settings))
                   ;; run on each new connection: PgJDBC sends the JVM's zone when
                   ;; it connects, so the zone is set right after; the search path
                   ;; is only public, so no other schema catches an app's table
                   (.setConnectionInitSql (str "SET TIME ZONE '" time-zone "'; SET search_path = public"))
                   ;; fixed, each with its reason in the decision "every value that
                   ;; sets behaviour is a setting": a fixed pool (no idle connections
                   ;; closed); start-pool! fails when it can't connect; statements
                   ;; commit by themselves outside run-in-transaction!; read
                   ;; committed, whatever the role's default; the driver's own
                   ;; tuning, which changes no answer
                   (.setMinimumIdle pool-size)
                   (.setIdleTimeout 0)
                   (.setInitializationFailTimeout 1)
                   (.setAutoCommit true)
                   (.setReadOnly false)
                   (.setTransactionIsolation "TRANSACTION_READ_COMMITTED")
                   (.setIsolateInternalQueries false)
                   (.setAllowPoolSuspension false)
                   (.setRegisterMbeans false)
                   (.addDataSourceProperty "ApplicationName" (str "cellar-" database))
                   (.addDataSourceProperty "prepareThreshold" "5")
                   (.addDataSourceProperty "defaultRowFetchSize" "0"))]
      (when password (.setPassword config password))
      (doseq [[k v] (tls-properties db-settings)]
        (.addDataSourceProperty config k v))
      (pool-lines/add-pool! pool-name (:log options))
      (try
        {:cellar/datasource (pool-lines/call-with-pool-on-thread pool-name #(HikariDataSource. config))
         :cellar/pool-name  pool-name}
        (catch Throwable e
          (pool-lines/remove-pool! pool-name)
          (throw e))))))

(defn stop-pool!
  "Closes the pool's connections."
  [{:cellar/keys [datasource pool-name]}]
  (try
    (pool-lines/call-with-pool-on-thread pool-name #(.close ^HikariDataSource datasource))
    (finally (pool-lines/remove-pool! pool-name))))

(defn call-with-connection
  "(f connection) on a connection of the pool, closed after; HikariCP's and
   PgJDBC's lines on this thread meanwhile are the pool's."
  [pool f]
  (pool-lines/call-with-pool-on-thread
   (:cellar/pool-name pool)
   #(with-open [connection (jdbc/get-connection (:cellar/datasource pool))]
      (f connection))))

;; -----------------------------------------------------------------------------
;; Queries

(defn make-query-from-sql-file
  "A query from a .sql file on the classpath, read now: a missing file fails
   when the namespace that defs the query loads. A changed file is read
   again when the def is evaluated again."
  [path]
  (let [url (or (io/resource path)
                (throw (ex-info (str "No SQL file " path) {:type :cellar/missing-file :path path})))]
    (assoc (params/turn-sql-text-into-statement (slurp url))
           :cellar/path path
           :cellar/tables-read (atom nil))))

(defn make-query-from-sql-text
  "A query from SQL text made by code, not read from a file: for statements
   code makes from data (a table's statements, made from its map).
   The name stands where a file's path would, in errors."
  [query-name sql-text]
  (assoc (params/turn-sql-text-into-statement sql-text)
         :cellar/path query-name
         :cellar/tables-read (atom nil)))

(defn- run-on-connection
  "Rows as a vector when the statement returns rows, else {:count n}. With
   :max-rows, Postgres sends at most that many rows. A query run for its
   rows (:rows? true) notes the tables it reads, for run-and-list-tables-read."
  [^Connection connection query params {:keys [max-rows rows?]}]
  (when rows?
    (tables-read/note-tables-of-query! connection query))
  (let [path   (:cellar/path query)
        values (mapv rows/turn-param-value-into-jdbc-value (params/list-param-values query params path))]
    (with-open [^PreparedStatement statement (jdbc/prepare connection (into [(:text-with-question-marks query)] values))]
      (when max-rows (.setMaxRows statement (int max-rows)))
      (if (.execute statement)
        (with-open [rs (.getResultSet statement)]
          (rows/list-rows-in-result-set rs path))
        {:count (.getLargeUpdateCount statement)}))))

(defn- run-query [pool-or-transaction query params options]
  (if-let [connection (:cellar/connection pool-or-transaction)]
    (run-on-connection connection query params options)
    (call-with-connection pool-or-transaction #(run-on-connection % query params options))))

(defn- throw-when-no-rows [result query]
  (when-not (vector? result)
    (throw (ex-info (str (:cellar/path query) " returns no rows; use run-statement!")
                    {:type :cellar/no-rows :file (:cellar/path query)}))))

(defn find-rows
  "Every row of a query that returns rows (a SELECT, or a write with
   RETURNING): a vector, empty when none."
  [pool-or-transaction query params]
  (let [result (run-query pool-or-transaction query params {:rows? true})]
    (throw-when-no-rows result query)
    result))

(defn find-one-row
  "The one row of a query, or nil. A second row throws; Postgres sends at
   most two, so a runaway query isn't read in full first."
  [pool-or-transaction query params]
  (let [result (run-query pool-or-transaction query params {:rows? true :max-rows 2})]
    (throw-when-no-rows result query)
    (when (second result)
      (throw (ex-info (str (:cellar/path query) " returned more than one row")
                      {:type :cellar/more-than-one-row :file (:cellar/path query)})))
    (first result)))

(defn run-statement!
  "Runs a statement for what it changes (an INSERT, UPDATE, DELETE).
   Returns how many rows it changed."
  [pool-or-transaction query params]
  (let [result (run-query pool-or-transaction query params {})]
    (if (vector? result) (count result) (:count result))))

(defn run-in-transaction!
  "Runs (f transaction) in one transaction: everything f runs on the
   transaction is kept when f returns, and undone when f throws (the throw
   goes on). Returns what f returns. Inside a transaction, it joins that one.
   Options, every key required: :read-only?, true and Postgres refuses every
   write in it."
  [pool-or-transaction options f]
  (settings/check-transaction-options options)
  (if (:cellar/connection pool-or-transaction)
    (f pool-or-transaction)
    (call-with-connection pool-or-transaction
                          (fn [connection]
                            (jdbc/with-transaction [connection connection {:read-only (:read-only? options)}]
                              (f {:cellar/connection connection}))))))
