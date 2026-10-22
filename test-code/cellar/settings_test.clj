(ns cellar.settings-test
  "Every value that sets behaviour is written out: a missing or unknown key
   is refused, named, before anything runs. The pool's own tests run when
   CELLAR_TEST_PORT is set."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cellar.api :as cellar]
   [cellar.test-settings :as test-settings :refer [column]])
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
    (let [{:keys [type errors]} (find-refusal #(cellar/start-pool! (dissoc settings :lock-timeout-ms :connection-wait-ms :tls-root-cert)))]
      (is (= :cellar/bad-settings type))
      (is (= #{:lock-timeout-ms :connection-wait-ms :tls-root-cert} (set (keys errors))))))
  (testing "an unknown one too"
    (is (= {:pool-szie ["disallowed key"]} (:errors (find-refusal #(cellar/start-pool! (assoc settings :pool-szie 3)))))))
  (testing "a value HikariCP would change with only a log line is refused"
    (is (contains? (:errors (find-refusal #(cellar/start-pool! (assoc settings :connection-max-lifetime-ms 20000))))
                   :connection-max-lifetime-ms))
    (is (= :cellar/bad-settings
           (:type (find-refusal #(cellar/start-pool! (assoc settings :connection-keepalive-every-ms 1800000))))))
    (is (= :cellar/bad-settings
           (:type (find-refusal #(cellar/start-pool! (assoc settings :leak-warning-after-ms 1000)))))))
  (testing "a root certificate is only for :verified"
    (is (= :cellar/bad-settings (:type (find-refusal #(cellar/start-pool! (assoc settings :tls-root-cert "/tmp/root.crt"))))))))

(def ^:private a-table
  {:table     :things
   :columns   [(column :thing/id :int :primary-key? true)]
   :indexes   []
   :gates     nil
   :on-change nil})

(deftest the-options-are-every-one-required
  (testing "run-in-transaction! says :read-only?"
    (is (= {:type :cellar/bad-options :errors {:read-only? ["missing required key"]}}
           (find-refusal #(cellar/run-in-transaction! nil {} (fn [_]))))))
  (testing "start-listening! says each of its numbers and both functions"
    (let [{:keys [type errors]} (find-refusal #(cellar/start-listening! nil "things" {:on-notifications (fn [_])}))]
      (is (= :cellar/bad-options type))
      (is (= #{:on-reconnect :batch-window-ms :check-stopped-every-ms :reconnect-after-ms :stop-wait-ms}
             (set (keys errors))))))
  (testing "each part of a plan says its time limits"
    (is (= {:type :cellar/bad-options :errors {:lock-timeout-ms ["missing required key"]}}
           (find-refusal #(cellar/run-instant-part! nil {:instant [] :slow [] :stops [] :changes-to-mark []}
                                                    {:statement-timeout-ms 1000}))))
    (is (= :cellar/bad-options
           (:type (find-refusal #(cellar/run-slow-part! nil {:slow []} {})))))
    (is (= {:type :cellar/bad-options :errors {:slow-part ["missing required key"]}}
           (find-refusal #(cellar/migrate! nil [a-table] [] {:instant-part {:statement-timeout-ms 1000 :lock-timeout-ms 1000}}))))))

(deftest a-table-map-writes-out-every-key-cellar-reads
  (testing "a table without :indexes, :gates or :on-change is refused, each named"
    (is (= {:type :cellar/bad-tables
            :errors [{:indexes ["missing required key"] :gates ["missing required key"] :on-change ["missing required key"]}]}
           (find-refusal #(cellar/make-plan-from-tables nil [(select-keys a-table [:table :columns])] [])))))
  (testing "a column writes out its five options"
    (let [{:keys [type errors]} (find-refusal #(cellar/make-plan-from-tables nil [(assoc a-table :columns [[:thing/id :int {:primary-key? true}]])] []))]
      (is (= :cellar/bad-tables type))
      (is (= #{:optional? :unique? :default :references}
             (set (keys (get-in errors [0 :columns 0 2])))))))
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
                                          :lock-timeout-ms 4000 :idle-in-transaction-timeout-ms 90000))]
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
