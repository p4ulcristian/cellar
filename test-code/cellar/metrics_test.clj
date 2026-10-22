(ns cellar.metrics-test
  "The pool's metrics on the meter it is handed: its connections and the
   threads waiting, the wait for a connection, its use, the timeouts,
   every query's time by its name (\"other\" for one made after the pool
   started), and a listener listening again. Runs when CELLAR_TEST_PORT is
   set."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cellar.api :as cellar]
   [cellar.test-settings :as test-settings :refer [find-metric-lines]]))

;; made before any pool starts: counted by its name
(def ^:private select-one
  (cellar/make-query-from-sql-text "metrics-test/select-one" "SELECT 1 AS one"))

(def ^:private sleep-a-little
  (cellar/make-query-from-sql-text "metrics-test/sleep" "SELECT pg_sleep(0.05) IS NULL AS slept"))

(defn- start-pool! [a-meter pool-size]
  (cellar/start-pool! (assoc (test-settings/make-pool-settings pool-size 5000) :connection-wait-ms 250)
                      {:log test-settings/log :meter a-meter}))

(deftest the-pool-and-its-queries-are-counted
  (if-not test-settings/port
    (println "cellar.metrics-test: skipped, CELLAR_TEST_PORT is not set")
    (let [meter (test-settings/make-meter)
          pool  (start-pool! meter 1)]
      (try
        (testing "the pool's connections are read when the text is written"
          (is (= ["cellar_pool_connections{state=\"active\"} 0"
                  "cellar_pool_connections{state=\"idle\"} 1"]
                 (find-metric-lines meter "cellar_pool_connections")))
          (is (= ["cellar_pool_waiting_threads 0"] (find-metric-lines meter "cellar_pool_waiting_threads"))))
        (cellar/find-one-row pool select-one {})
        (cellar/find-one-row pool select-one {})
        (cellar/run-in-transaction! pool {:read-only? true} #(cellar/find-one-row % sleep-a-little {}))
        (cellar/find-one-row pool (cellar/make-query-from-sql-text "metrics-test/made-after" "SELECT 2 AS two") {})
        (testing "every query's time, by its name; one made after the pool started is other"
          (is (= ["cellar_query_seconds_count{query=\"metrics-test/select-one\"} 2"
                  "cellar_query_seconds_count{query=\"metrics-test/sleep\"} 1"
                  "cellar_query_seconds_count{query=\"other\"} 1"]
                 (find-metric-lines meter "cellar_query_seconds_count")))
          (is (= ["cellar_query_seconds_bucket{query=\"metrics-test/sleep\",le=\"0.01\"} 0"
                  "cellar_query_seconds_bucket{query=\"metrics-test/sleep\",le=\"0.1\"} 1"]
                 (filterv #(re-find #"le=\"0\.0?1\"" %) (find-metric-lines meter "cellar_query_seconds_bucket{query=\"metrics-test/sleep\"")))
              "the settings' buckets"))
        (testing "the wait for a connection and its use, each time one is lent"
          (is (= ["cellar_connection_acquire_seconds_count 4"] (find-metric-lines meter "cellar_connection_acquire_seconds_count")))
          (is (= ["cellar_connection_usage_seconds_count 4"] (find-metric-lines meter "cellar_connection_usage_seconds_count"))))
        (testing "a wait past :connection-wait-ms is a timeout"
          (is (= ["cellar_connection_timeouts_total 0"] (find-metric-lines meter "cellar_connection_timeouts_total")))
          (let [holding (promise)
                held    (future (cellar/run-in-transaction! pool {:read-only? true}
                                                            (fn [_] (deliver holding true) (Thread/sleep 1000))))]
            (deref holding 2000 nil)
            (is (thrown? java.sql.SQLException (cellar/find-one-row pool select-one {})))
            @held)
          (is (= ["cellar_connection_timeouts_total 1"] (find-metric-lines meter "cellar_connection_timeouts_total"))))
        (testing "a meter holds one pool's metrics: a second pool on it is refused before it connects"
          (is (= :meter/bad-metrics
                 (try (start-pool! meter 1) :started
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
        (finally (cellar/stop-pool! pool)))
      (testing "a closed pool has no connections to read"
        (is (= [] (find-metric-lines meter "cellar_pool_connections{")))))))

(deftest listening-again-is-counted
  (if-not test-settings/port
    (println "cellar.metrics-test: skipped, CELLAR_TEST_PORT is not set")
    (let [meter    (test-settings/make-meter)
          pool     (start-pool! meter 2)
          again    (promise)
          listener (cellar/start-listening! pool "cellar_metrics_test"
                                            {:log test-settings/log :meter meter
                                             :on-notifications (fn [_]) :on-reconnect #(deliver again true)
                                             :batch-window-ms 0 :check-stopped-every-ms 100
                                             :reconnect-after-ms 100 :stop-wait-ms 2000})]
      (try
        (is (= ["cellar_listening_reconnects_total 0"] (find-metric-lines meter "cellar_listening_reconnects_total")))
        (Thread/sleep 300)
        (is (true? (cellar/is-listening? listener)) "listening once its LISTEN ran")
        (cellar/find-rows pool (cellar/make-query-from-sql-text
                                "end the listener"
                                "SELECT pg_terminate_backend(pid) AS ended FROM pg_stat_activity WHERE query = 'LISTEN cellar_metrics_test'")
                          {})
        (is (true? (deref again 5000 false)))
        (is (true? (cellar/is-listening? listener)) "and again after the break")
        (is (= ["cellar_listening_reconnects_total 1"] (find-metric-lines meter "cellar_listening_reconnects_total")))
        (finally
          (cellar/stop-listening! listener)
          (is (false? (cellar/is-listening? listener)) "not once stopped")
          (cellar/stop-pool! pool))))))
