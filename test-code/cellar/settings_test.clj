(ns cellar.settings-test
  "Every value that sets behaviour is written out: a missing or unknown key
   is refused, named, before anything runs. The pool's own tests run when
   CELLAR_TEST_PORT is set."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cellar.api :as cellar]
   [cellar.tables.desired :as desired]
   [cellar.test-settings :as test-settings])
  (:import
   (com.zaxxer.hikari HikariDataSource)))

(defn- find-refusal
  "{:type … :errors …} of what f threw, or :nothing."
  [f]
  (try (f) :nothing
       (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:type :errors]))))

(def ^:private settings (test-settings/make-pool-settings 1 5000))

(deftest the-settings-are-every-one-required
  (testing "a missing setting is refused, each named, before anything connects"
    (let [{:keys [type errors]} (find-refusal #(cellar/start-pool! (dissoc settings :lock-timeout-ms :connection-wait-ms :tls-root-cert) (test-settings/make-pool-options)))]
      (is (= :cellar/bad-settings type))
      (is (= #{:lock-timeout-ms :connection-wait-ms :tls-root-cert} (set (keys errors))))))
  (testing "an unknown one too"
    (is (= {:pool-szie ["disallowed key"]} (:errors (find-refusal #(cellar/start-pool! (assoc settings :pool-szie 3) (test-settings/make-pool-options)))))))
  (testing "a value HikariCP would change with only a log line is refused"
    (is (contains? (:errors (find-refusal #(cellar/start-pool! (assoc settings :connection-max-lifetime-ms 20000) (test-settings/make-pool-options))))
                   :connection-max-lifetime-ms))
    (is (= :cellar/bad-settings
           (:type (find-refusal #(cellar/start-pool! (assoc settings :connection-keepalive-every-ms 1800000) (test-settings/make-pool-options))))))
    (is (= :cellar/bad-settings
           (:type (find-refusal #(cellar/start-pool! (assoc settings :leak-warning-after-ms 1000) (test-settings/make-pool-options)))))))
  (testing "a root certificate is only for :verified"
    (is (= :cellar/bad-settings (:type (find-refusal #(cellar/start-pool! (assoc settings :tls-root-cert "/tmp/root.crt") (test-settings/make-pool-options))))))))

(def ^:private a-table
  {:table     :things
   :columns   [[:thing/id :int {:primary-key? true}]]
   :indexes   []
   :gates     nil
   :on-change nil})

(deftest the-options-are-every-one-required
  (testing "start-pool! takes the app's log and meter, before anything connects"
    (is (= {:type :cellar/bad-options :errors {:log ["missing required key"] :meter ["missing required key"]}}
           (find-refusal #(cellar/start-pool! settings {}))))
    (is (= {:type :cellar/bad-options :errors {:meter ["should be the app's meter, made by meter.api/make-meter"]}}
           (find-refusal #(cellar/start-pool! settings (assoc (test-settings/make-pool-options) :meter nil)))))
    (is (= {:type :meter/not-a-meter}
           (find-refusal #(cellar/start-pool! settings (assoc (test-settings/make-pool-options) :meter {}))))))
  (testing "the histograms' buckets are settings, meter checks they go up"
    (is (= {:metric-buckets ["missing required key"]}
           (:errors (find-refusal #(cellar/start-pool! (dissoc settings :metric-buckets) (test-settings/make-pool-options))))))
    (is (= :meter/bad-metrics
           (:type (find-refusal #(cellar/start-pool! (assoc-in settings [:metric-buckets :query-seconds] [1 1])
                                                     (test-settings/make-pool-options)))))))
  (testing "run-in-transaction! says :read-only?"
    (is (= {:type :cellar/bad-options :errors {:read-only? ["missing required key"]}}
           (find-refusal #(cellar/run-in-transaction! nil {} (fn [_]))))))
  (testing "start-listening! says each of its numbers and both functions"
    (let [{:keys [type errors]} (find-refusal #(cellar/start-listening! nil "things" {:on-notifications (fn [_])}))]
      (is (= :cellar/bad-options type))
      (is (= #{:log :meter :on-reconnect :batch-window-ms :check-stopped-every-ms :reconnect-after-ms :stop-wait-ms}
             (set (keys errors))))))
  (testing "a function that writes a line takes the app's log, a function"
    (is (= {:type :cellar/bad-options :errors {:log ["missing required key"]}}
           (find-refusal #(cellar/run-and-list-tables-read {} (fn [])))))
    (is (= {:type :cellar/bad-options
            :errors {:log ["should be the app's log function, (log level event data), made by logbook"]}}
           (find-refusal #(cellar/run-and-list-tables-read {:log :stdout} (fn []))))))
  (testing "each part of a plan says its time limits"
    (is (= {:type :cellar/bad-options :errors {:lock-timeout-ms ["missing required key"]}}
           (find-refusal #(cellar/run-instant-part! nil {:instant [] :slow [] :stops [] :changes-to-mark []}
                                                    {:statement-timeout-ms 1000}))))
    (is (= :cellar/bad-options
           (:type (find-refusal #(cellar/run-slow-part! nil {:slow []} {})))))
    (is (= {:type :cellar/bad-options :errors {:slow-part ["missing required key"] :log ["missing required key"]}}
           (find-refusal #(cellar/migrate! nil [a-table] [] {:instant-part {:statement-timeout-ms 1000 :lock-timeout-ms 1000}}))))))

(deftest a-table-map-writes-out-every-key-cellar-reads
  (testing "a table without :indexes, :gates or :on-change is refused, each named"
    (is (= {:type :cellar/bad-tables
            :errors [{:indexes ["missing required key"] :gates ["missing required key"] :on-change ["missing required key"]}]}
           (find-refusal #(cellar/make-plan-from-tables nil [(select-keys a-table [:table :columns])] [])))))
  (testing "a column says only the options it has; an unsaid one is no or none"
    (is (= [:map [:thing/id :int] [:thing/note :string] [:thing/when {:optional true} [:maybe :time/instant]]]
           (cellar/turn-table-into-row-shape (assoc a-table :columns [[:thing/id :int {:primary-key? true}]
                                                                      [:thing/note :string]
                                                                      [:thing/when :time/instant {:optional? true}]]))))
    (let [[[_ things]] (seq (desired/turn-table-maps-into-desired [(assoc a-table :columns [[:thing/id :int]])]))]
      (is (= [{:name "thing__id" :not-null? true :default nil}]
             (mapv #(select-keys % [:name :not-null? :default]) (:columns things))))
      (is (= [[] {} {}] [(:primary-key things) (:uniques things) (:foreign-keys things)]))))
  (testing "an unknown option is refused, named"
    (is (= {:type :cellar/bad-tables :errors [{:columns [[nil nil {:primary? ["disallowed key"]}]]}]}
           (find-refusal #(cellar/make-plan-from-tables nil [(assoc a-table :columns [[:thing/id :int {:primary? true}]])] [])))))
  (testing "a column is [key type] or [key type options]"
    (is (= {:type :cellar/bad-tables :errors [{:columns [["a column is [key type] or [key type options]"]]}]}
           (find-refusal #(cellar/make-plan-from-tables nil [(assoc a-table :columns [[:thing/id]])] [])))))
  (testing "gates write out all four operations"
    (is (= :cellar/bad-tables
           (:type (find-refusal #(cellar/turn-table-into-row-shape (assoc a-table :gates {:read "true"})))))))
  (testing "another library's keys ride along"
    (is (= [:map [:thing/id :int]] (cellar/turn-table-into-row-shape (assoc a-table :doorbell [:silent "why"]))))))

(def ^:private show-limits
  (cellar/make-query-from-sql-text "the limits"
                                   "SELECT current_setting('statement_timeout') AS statement_timeout, current_setting('lock_timeout') AS lock_timeout, current_setting('idle_in_transaction_session_timeout') AS idle"))

(deftest the-pool-is-what-the-settings-say
  (if-not test-settings/port
    (println "cellar.settings-test: skipped, CELLAR_TEST_PORT is not set")
    (let [pool (cellar/start-pool! (assoc settings
                                          :connection-wait-ms 7000 :connection-check-wait-ms 3000
                                          :connection-max-lifetime-ms 600000 :connection-keepalive-every-ms 60000
                                          :leak-warning-after-ms 20000
                                          :lock-timeout-ms 4000 :idle-in-transaction-timeout-ms 90000) (test-settings/make-pool-options))]
      (try
        (testing "HikariCP has each, unchanged"
          (let [^HikariDataSource datasource (:cellar/datasource pool)]
            (is (= [1 1 7000 3000 600000 60000 20000 0]
                   [(.getMaximumPoolSize datasource) (.getMinimumIdle datasource)
                    (.getConnectionTimeout datasource) (.getValidationTimeout datasource)
                    (.getMaxLifetime datasource) (.getKeepaliveTime datasource)
                    (.getLeakDetectionThreshold datasource) (.getIdleTimeout datasource)]))))
        (testing "the session has its limits from the first statement"
          (is (= {:statement-timeout "5s" :lock-timeout "4s" :idle "90s"}
                 (cellar/find-one-row pool show-limits {}))))
        (testing "the slow part runs under its own limits, and the connection gets the pool's back"
          (cellar/run-slow-part! pool {:slow [{:sql "SELECT 1 AS one" :why "a statement"}]}
                                 {:statement-timeout-ms :none :lock-timeout-ms 1000})
          (is (= {:statement-timeout "5s" :lock-timeout "4s" :idle "90s"}
                 (cellar/find-one-row pool show-limits {}))))
        (testing "the instant part's limits hold for its transaction only"
          (cellar/run-instant-part! pool {:instant [] :stops [] :changes-to-mark []}
                                    {:statement-timeout-ms 2000 :lock-timeout-ms 1000})
          (is (= {:statement-timeout "5s" :lock-timeout "4s" :idle "90s"}
                 (cellar/find-one-row pool show-limits {}))))
        (finally (cellar/stop-pool! pool))))))
