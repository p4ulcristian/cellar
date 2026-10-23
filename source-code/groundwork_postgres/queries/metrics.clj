(ns groundwork-postgres.queries.metrics
  "groundwork-postgres's metrics, declared on the meter a pool or a listener is handed:

     groundwork_postgres_pool_connections{state}         gauge, read: active, idle
     groundwork_postgres_pool_waiting_threads            gauge, read: threads waiting for a connection
     groundwork_postgres_connection_acquire_seconds      histogram: the wait for a connection
     groundwork_postgres_connection_usage_seconds        histogram: how long one was held
     groundwork_postgres_connection_timeouts_total       counter: waits past :connection-wait-ms
     groundwork_postgres_query_seconds{query}            histogram: every query run, by its name
     groundwork_postgres_listening_reconnects_total      counter: listening again after a break

   A query's name is its file's path, or the name make-query-from-sql-text
   was given. Each query made is noted here when it is made; start-pool!
   declares the names noted by then as the query label's set, so a query
   made after the pool started is counted as \"other\". A meter holds one
   pool's metrics: the read gauges read that pool."
  (:require
   [groundwork-metrics.api :as metrics])
  (:import
   (com.zaxxer.hikari HikariDataSource HikariPoolMXBean)
   (com.zaxxer.hikari.metrics IMetricsTracker MetricsTrackerFactory)))

(defonce ^:private query-names
  ;; every query's name, noted when it is made
  (atom #{}))

(defn note-query-name!
  "Notes a query's name, so a pool started after it counts it by name."
  [query-name]
  (swap! query-names conj query-name))

(defn- read-pool
  "The pool's connections by state, now; none once it is closed."
  [datasource-holder f]
  (fn []
    (let [^HikariDataSource datasource @datasource-holder]
      (if (or (nil? datasource) (.isClosed datasource))
        {}
        (f (.getHikariPoolMXBean datasource))))))

(defn declare-pool-metrics!
  "Declares the pool's metrics on the meter. The read gauges read the
   datasource datasource-holder will hold; the buckets are the settings'
   :metric-buckets."
  [meter {{:keys [query-seconds connection-acquire-seconds connection-usage-seconds]} :metric-buckets} datasource-holder]
  (metrics/declare-metrics!
   meter
   [{:name    :groundwork-postgres/pool-connections
     :type    :gauge
     :help    "The pool's connections, by state: active (lent out) or idle."
     :labels  {:state #{:active :idle}}
     :buckets nil
     :read    (read-pool datasource-holder
                         (fn [^HikariPoolMXBean bean]
                           {{:state :active} (.getActiveConnections bean)
                            {:state :idle}   (.getIdleConnections bean)}))}
    {:name    :groundwork-postgres/pool-waiting-threads
     :type    :gauge
     :help    "Threads waiting for one of the pool's connections."
     :labels  {}
     :buckets nil
     :read    (read-pool datasource-holder
                         (fn [^HikariPoolMXBean bean] {{} (.getThreadsAwaitingConnection bean)}))}
    {:name    :groundwork-postgres/connection-acquire-seconds
     :type    :histogram
     :help    "Time a caller waited for one of the pool's connections, in seconds."
     :labels  {}
     :buckets connection-acquire-seconds
     :read    nil}
    {:name    :groundwork-postgres/connection-usage-seconds
     :type    :histogram
     :help    "Time a connection was held before it went back to the pool, in seconds."
     :labels  {}
     :buckets connection-usage-seconds
     :read    nil}
    {:name    :groundwork-postgres/connection-timeouts-total
     :type    :counter
     :help    "Waits for a connection that passed :connection-wait-ms and failed."
     :labels  {}
     :buckets nil
     :read    nil}
    {:name    :groundwork-postgres/query-seconds
     :type    :histogram
     :help    "Time a query took to run, in seconds, by its name (its .sql file's path)."
     ;; text names only (a name meter can't write is counted as "other");
     ;; "other" is groundwork-metrics's own: a query of that name is counted there anyway
     :labels  {:query (disj (set (filter string? @query-names)) "other")}
     :buckets query-seconds
     :read    nil}]))

(defn make-tracker-factory
  "HikariCP's metrics tracker, counting into the meter: the wait for a
   connection, how long one was held, and the waits that timed out."
  [meter]
  (reify MetricsTrackerFactory
    (create [_ _pool-name _pool-stats]
      (reify IMetricsTracker
        (recordConnectionAcquiredNanos [_ nanos]
          (metrics/record-in-histogram! meter :groundwork-postgres/connection-acquire-seconds {} (/ nanos 1e9)))
        (recordConnectionUsageMillis [_ millis]
          (metrics/record-in-histogram! meter :groundwork-postgres/connection-usage-seconds {} (/ millis 1e3)))
        (recordConnectionTimeout [_]
          (metrics/add-to-counter! meter :groundwork-postgres/connection-timeouts-total {} 1))))))

(defn record-query!
  "Records a query's time by its name. Every pool and transaction groundwork-postgres
   makes carries its meter; a map made elsewhere without one records
   nothing."
  [meter query-name started-nanos]
  (when meter
    (metrics/record-in-histogram! meter :groundwork-postgres/query-seconds {:query query-name}
                                (/ (- (System/nanoTime) started-nanos) 1e9))))

(defn declare-listening-metrics!
  "Declares the listeners' metric on the meter."
  [meter]
  (metrics/declare-metrics!
   meter
   [{:name    :groundwork-postgres/listening-reconnects-total
     :type    :counter
     :help    "Times a listener connected and listened again after a break."
     :labels  {}
     :buckets nil
     :read    nil}]))

(defn count-reconnect!
  [meter]
  (metrics/add-to-counter! meter :groundwork-postgres/listening-reconnects-total {} 1))
