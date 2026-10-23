(ns groundwork-postgres.settings-test
  "Every value that sets behaviour is written out: a missing or unknown key
   is refused, named, before anything runs. The pool's own tests run when
   POSTGRES_TEST_PORT is set."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork-postgres.api :as postgres]
   [groundwork-postgres.settings :as postgres-settings]
   [groundwork-postgres.tables.desired :as desired]
   [groundwork-postgres.test-settings :as test-settings]
   [groundwork-settings.api :as settings])
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
    (let [{:keys [type errors]} (find-refusal #(postgres/start-pool! (dissoc settings :lock-timeout-ms :connection-wait-ms :tls-root-cert) (test-settings/make-pool-options)))]
      (is (= :groundwork-postgres/bad-settings type))
      (is (= #{:lock-timeout-ms :connection-wait-ms :tls-root-cert} (set (keys errors))))))
  (testing "an unknown one too"
    (is (= {:pool-szie ["disallowed key"]} (:errors (find-refusal #(postgres/start-pool! (assoc settings :pool-szie 3) (test-settings/make-pool-options)))))))
  (testing "a value HikariCP would change with only a log line is refused"
    (is (contains? (:errors (find-refusal #(postgres/start-pool! (assoc settings :connection-max-lifetime-ms 20000) (test-settings/make-pool-options))))
                   :connection-max-lifetime-ms))
    (is (= :groundwork-postgres/bad-settings
           (:type (find-refusal #(postgres/start-pool! (assoc settings :connection-keepalive-every-ms 1800000) (test-settings/make-pool-options))))))
    (is (= :groundwork-postgres/bad-settings
           (:type (find-refusal #(postgres/start-pool! (assoc settings :leak-warning-after-ms 1000) (test-settings/make-pool-options)))))))
  (testing "the password is a secret: written out as text, it is refused, and the text is never said"
    (let [refusal (find-refusal #(postgres/start-pool! (assoc settings :password "written-out-password") (test-settings/make-pool-options)))]
      (is (= {:type :groundwork-postgres/bad-settings :errors {:password ["should be a #secret, read from the settings' secrets source"]}}
             refusal))
      (is (not (re-find #"written-out-password" (pr-str refusal)))))
    (is (= :nothing (find-refusal #(postgres-settings/check-settings (assoc settings :password (settings/make-secret :groundwork-postgres/password "pw")))))))
  (testing "a root certificate is only for :verified"
    (is (= :groundwork-postgres/bad-settings (:type (find-refusal #(postgres/start-pool! (assoc settings :tls-root-cert "/tmp/root.crt") (test-settings/make-pool-options))))))))

(def ^:private a-table
  {:table     :things
   :columns   [[:thing/id :int {:primary-key? true}]]
   :indexes   []
   :uniques   []
   :checks    []
   :references []
   :triggers  []
   :gates     nil
   :on-change nil})

(deftest the-options-are-every-one-required
  (testing "start-pool! takes the app's log and meter, before anything connects"
    (is (= {:type :groundwork-postgres/bad-options :errors {:log ["missing required key"] :meter ["missing required key"]}}
           (find-refusal #(postgres/start-pool! settings {}))))
    (is (= {:type :groundwork-postgres/bad-options :errors {:meter ["should be the app's meter, made by groundwork-metrics.api/make-meter"]}}
           (find-refusal #(postgres/start-pool! settings (assoc (test-settings/make-pool-options) :meter nil)))))
    (is (= {:type :groundwork-metrics/not-a-meter}
           (find-refusal #(postgres/start-pool! settings (assoc (test-settings/make-pool-options) :meter {}))))))
  (testing "the histograms' buckets are settings, meter checks they go up"
    (is (= {:metric-buckets ["missing required key"]}
           (:errors (find-refusal #(postgres/start-pool! (dissoc settings :metric-buckets) (test-settings/make-pool-options))))))
    (is (= :groundwork-metrics/bad-metrics
           (:type (find-refusal #(postgres/start-pool! (assoc-in settings [:metric-buckets :query-seconds] [1 1])
                                                     (test-settings/make-pool-options)))))))
  (testing "run-in-transaction! says :read-only? and the caller's :log"
    (is (= {:type :groundwork-postgres/bad-options :errors {:read-only? ["missing required key"] :log ["missing required key"]}}
           (find-refusal #(postgres/run-in-transaction! nil {} (fn [_]))))))
  (testing "start-listening! says each of its numbers and both functions"
    (let [{:keys [type errors]} (find-refusal #(postgres/start-listening! nil "things" {:on-notifications (fn [_])}))]
      (is (= :groundwork-postgres/bad-options type))
      (is (= #{:log :meter :on-reconnect :batch-window-ms :check-stopped-every-ms :reconnect-after-ms :stop-wait-ms}
             (set (keys errors))))))
  (testing "a function that writes a line takes the app's log, a function"
    (is (= {:type :groundwork-postgres/bad-options :errors {:log ["missing required key"]}}
           (find-refusal #(postgres/run-and-list-tables-read {} (fn [])))))
    (is (= {:type :groundwork-postgres/bad-options
            :errors {:log ["should be the app's log function, (log level event data), made by groundwork-logs"]}}
           (find-refusal #(postgres/run-and-list-tables-read {:log :stdout} (fn []))))))
  (testing "each part of a plan says its time limits"
    (is (= {:type :groundwork-postgres/bad-options :errors {:lock-timeout-ms ["missing required key"]}}
           (find-refusal #(postgres/run-instant-part! nil {:instant [] :slow [] :stops [] :changes-to-mark []}
                                                    {:statement-timeout-ms 1000}))))
    (is (= :groundwork-postgres/bad-options
           (:type (find-refusal #(postgres/run-slow-part! nil {:slow []} {})))))
    (is (= {:type :groundwork-postgres/bad-options :errors {:slow-part ["missing required key"] :log ["missing required key"]}}
           (find-refusal #(postgres/migrate! nil (test-settings/declare-tables [a-table]) [] {:instant-part {:statement-timeout-ms 1000 :lock-timeout-ms 1000}}))))))

(deftest a-table-map-writes-out-every-key-postgres-reads
  (testing "a table without :indexes, :uniques, :references, :triggers, :gates or :on-change is refused, each named"
    (is (= {:type :groundwork-postgres/bad-tables
            :errors {:tables [{:indexes ["missing required key"] :uniques ["missing required key"] :checks ["missing required key"]
                      :references ["missing required key"] :triggers ["missing required key"]
                      :gates ["missing required key"] :on-change ["missing required key"]}]}}
           (find-refusal #(postgres/make-plan-from-tables nil (test-settings/declare-tables [(select-keys a-table [:table :columns])]) [])))))
  (testing "a column says only the options it has; an unsaid one is no or none"
    (is (= [:map [:thing/id :int] [:thing/note :string] [:thing/when {:optional true} [:maybe :time/instant]]]
           (postgres/turn-table-into-row-shape (assoc a-table :columns [[:thing/id :int {:primary-key? true}]
                                                                      [:thing/note :string]
                                                                      [:thing/when :time/instant {:optional? true}]]))))
    (let [[[_ things]] (seq (desired/turn-table-maps-into-desired [(assoc a-table :columns [[:thing/id :int]])]))]
      (is (= [{:name "thing__id" :not-null? true :default-sql nil}]
             (mapv #(select-keys % [:name :not-null? :default-sql]) (:columns things))))
      (is (= [[] {} {}] [(:primary-key things) (:uniques things) (:foreign-keys things)]))))
  (testing "an unknown option is refused, named"
    (is (= {:type :groundwork-postgres/bad-tables :errors {:tables [{:columns [[nil nil {:primary? ["disallowed key"]}]]}]}}
           (find-refusal #(postgres/make-plan-from-tables nil (test-settings/declare-tables [(assoc a-table :columns [[:thing/id :int {:primary? true}]])]) [])))))
  (testing "a column is [key type] or [key type options]"
    (is (= {:type :groundwork-postgres/bad-tables :errors {:tables [{:columns [["a column is [key type] or [key type options]"]]}]}}
           (find-refusal #(postgres/make-plan-from-tables nil (test-settings/declare-tables [(assoc a-table :columns [[:thing/id]])]) [])))))
  (testing "gates write out all four operations"
    (is (= :groundwork-postgres/bad-tables
           (:type (find-refusal #(postgres/turn-table-into-row-shape (assoc a-table :gates {:read "true"})))))))
  (testing "another library's keys ride along"
    (is (= [:map [:thing/id :int]] (postgres/turn-table-into-row-shape (assoc a-table :live-updates [:silent "why"]))))))

(def ^:private show-limits
  (postgres/make-query-from-sql-text "the limits"
                                   "SELECT current_setting('statement_timeout') AS statement_timeout, current_setting('lock_timeout') AS lock_timeout, current_setting('idle_in_transaction_session_timeout') AS idle"))

(deftest the-pool-is-what-the-settings-say
  (if-not test-settings/port
    (println "groundwork-postgres.settings-test: skipped, POSTGRES_TEST_PORT is not set")
    (let [pool (postgres/start-pool! (assoc settings
                                          :connection-wait-ms 7000 :connection-check-wait-ms 3000
                                          :connection-max-lifetime-ms 600000 :connection-keepalive-every-ms 60000
                                          :leak-warning-after-ms 20000
                                          :lock-timeout-ms 4000 :idle-in-transaction-timeout-ms 90000) (test-settings/make-pool-options))]
      (try
        (testing "HikariCP has each, unchanged"
          (let [^HikariDataSource datasource (:groundwork-postgres/datasource pool)]
            (is (= [1 1 7000 3000 600000 60000 20000 0]
                   [(.getMaximumPoolSize datasource) (.getMinimumIdle datasource)
                    (.getConnectionTimeout datasource) (.getValidationTimeout datasource)
                    (.getMaxLifetime datasource) (.getKeepaliveTime datasource)
                    (.getLeakDetectionThreshold datasource) (.getIdleTimeout datasource)]))))
        (testing "the session has its limits from the first statement"
          (is (= {:statement-timeout "5s" :lock-timeout "4s" :idle "90s"}
                 (postgres/find-one-row pool show-limits {}))))
        (testing "the slow part runs under its own limits, and the connection gets the pool's back"
          (postgres/run-slow-part! pool {:slow [{:sql "SELECT 1 AS one" :why "a statement"}]}
                                 {:statement-timeout-ms :none :lock-timeout-ms 1000})
          (is (= {:statement-timeout "5s" :lock-timeout "4s" :idle "90s"}
                 (postgres/find-one-row pool show-limits {}))))
        (testing "the instant part's limits hold for its transaction only"
          (postgres/run-instant-part! pool {:instant [] :stops [] :changes-to-mark []}
                                    {:statement-timeout-ms 2000 :lock-timeout-ms 1000})
          (is (= {:statement-timeout "5s" :lock-timeout "4s" :idle "90s"}
                 (postgres/find-one-row pool show-limits {}))))
        (finally (postgres/stop-pool! pool))))))
