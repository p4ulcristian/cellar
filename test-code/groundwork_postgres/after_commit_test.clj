(ns groundwork-postgres.after-commit-test
  "A transaction keeps work to run after its commit. The Postgres tests run
   when POSTGRES_TEST_PORT is set."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork-postgres.api :as postgres]
   [groundwork-postgres.test-settings :as test-settings]))

(def ^:private port test-settings/port)

(def ^:private select-one (postgres/make-query-from-sql-text "test: one" "SELECT 1 AS one__value"))

(deftest work-runs-after-the-commit-and-never-after-a-rollback
  (if-not port
    (println "groundwork-postgres.after-commit-test: skipped, POSTGRES_TEST_PORT is not set")
    (let [lines (atom [])
          pool  (postgres/start-pool! (test-settings/make-pool-settings 2 5000)
                                    {:log (test-settings/make-log lines) :meter (test-settings/make-meter)})
          ran   (atom [])]
      (try
        (testing "in order, after COMMIT, on the same thread, a nested transaction's with the outer's"
          (let [thread (Thread/currentThread)
                answer (postgres/run-in-transaction! pool {:read-only? false :log test-settings/log}
                         (fn [transaction]
                           (postgres/run-after-commit! transaction #(swap! ran conj [:first (identical? thread (Thread/currentThread))]))
                           (postgres/run-in-transaction! transaction {:read-only? false :log test-settings/log}
                             (fn [inner] (postgres/run-after-commit! inner #(swap! ran conj [:nested true]))))
                           (is (= [] @ran) "nothing before the commit")
                           (postgres/find-one-row transaction select-one {})))]
            (is (= {:one/value 1} answer) "the transaction's answer")
            (is (= [[:first true] [:nested true]] @ran))))
        (testing "never after a rollback"
          (reset! ran [])
          (is (thrown? clojure.lang.ExceptionInfo
                       (postgres/run-in-transaction! pool {:read-only? false :log test-settings/log}
                         (fn [transaction]
                           (postgres/run-after-commit! transaction #(swap! ran conj :rolled-back))
                           (throw (ex-info "undo" {}))))))
          (is (= [] @ran)))
        (testing "a failure is written to the caller's log, the transaction's, not the pool's; the rest still runs"
          (reset! ran [])
          (reset! lines [])
          (let [callers (atom [])
                inners  (atom [])]
            (postgres/run-in-transaction! pool {:read-only? false :log (test-settings/make-log callers)}
              (fn [transaction]
                (postgres/run-after-commit! transaction #(throw (ex-info "cache gone" {})))
                (postgres/run-in-transaction! transaction {:read-only? false :log (test-settings/make-log inners)}
                  (fn [inner] (postgres/run-after-commit! inner #(throw (ex-info "inner gone" {})))))
                (postgres/run-after-commit! transaction #(swap! ran conj :second))))
            (is (= [:second] @ran))
            (is (= [[:error :groundwork-postgres/after-commit-failed "cache gone" (:groundwork-postgres/pool-name pool)]]
                   (for [[level event data] @callers]
                     [level event (ex-message (:error data)) (:groundwork-postgres/pool data)]))
                "the caller's log (a message's, with its request id), naming the pool")
            (is (= [[:error :groundwork-postgres/after-commit-failed "inner gone"]]
                   (for [[level event data] @inners] [level event (ex-message (:error data))]))
                "work kept inside a nested call goes to that call's log")
            (is (= [] (filter #(= :groundwork-postgres/after-commit-failed (second %)) @lines)) "none to the pool's")))
        (testing "run-in-transaction! takes the caller's log, required; one opened with :log nil keeps no work"
          (is (= {:log ["missing required key"]}
                 (:errors (ex-data (try (postgres/run-in-transaction! pool {:read-only? false} (fn [_])) (catch Exception e e))))))
          (is (= :groundwork-postgres/transaction-without-log
                 (:type (ex-data (try (postgres/run-in-transaction! pool {:read-only? false :log nil}
                                        (fn [transaction] (postgres/run-after-commit! transaction #(swap! ran conj :never))))
                                      (catch Exception e e))))))
          (is (= [:second] @ran) "nothing kept"))
        (testing "a pool is refused: there is no commit to wait for"
          (is (= :groundwork-postgres/not-a-transaction
                 (:type (ex-data (try (postgres/run-after-commit! pool #(swap! ran conj :never)) (catch Exception e e)))))))
        (finally (postgres/stop-pool! pool))))))
