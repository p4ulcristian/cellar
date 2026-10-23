(ns cellar.after-commit-test
  "A transaction keeps work to run after its commit. The Postgres tests run
   when CELLAR_TEST_PORT is set."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cellar.api :as cellar]
   [cellar.test-settings :as test-settings]))

(def ^:private port test-settings/port)

(def ^:private select-one (cellar/make-query-from-sql-text "test: one" "SELECT 1 AS one__value"))

(deftest work-runs-after-the-commit-and-never-after-a-rollback
  (if-not port
    (println "cellar.after-commit-test: skipped, CELLAR_TEST_PORT is not set")
    (let [lines (atom [])
          pool  (cellar/start-pool! (test-settings/make-pool-settings 2 5000)
                                    {:log (test-settings/make-log lines) :meter (test-settings/make-meter)})
          ran   (atom [])]
      (try
        (testing "in order, after COMMIT, on the same thread, a nested transaction's with the outer's"
          (let [thread (Thread/currentThread)
                answer (cellar/run-in-transaction! pool {:read-only? false}
                         (fn [transaction]
                           (cellar/run-after-commit! transaction #(swap! ran conj [:first (identical? thread (Thread/currentThread))]))
                           (cellar/run-in-transaction! transaction {:read-only? false}
                             (fn [inner] (cellar/run-after-commit! inner #(swap! ran conj [:nested true]))))
                           (is (= [] @ran) "nothing before the commit")
                           (cellar/find-one-row transaction select-one {})))]
            (is (= {:one/value 1} answer) "the transaction's answer")
            (is (= [[:first true] [:nested true]] @ran))))
        (testing "never after a rollback"
          (reset! ran [])
          (is (thrown? clojure.lang.ExceptionInfo
                       (cellar/run-in-transaction! pool {:read-only? false}
                         (fn [transaction]
                           (cellar/run-after-commit! transaction #(swap! ran conj :rolled-back))
                           (throw (ex-info "undo" {}))))))
          (is (= [] @ran)))
        (testing "a failure is logged; the rest still runs"
          (reset! ran [])
          (reset! lines [])
          (cellar/run-in-transaction! pool {:read-only? false}
            (fn [transaction]
              (cellar/run-after-commit! transaction #(throw (ex-info "cache gone" {})))
              (cellar/run-after-commit! transaction #(swap! ran conj :second))))
          (is (= [:second] @ran))
          (is (= [[:error :cellar/after-commit-failed "cache gone"]]
                 (for [[level event data] @lines :when (= :cellar/after-commit-failed event)]
                   [level event (ex-message (:error data))]))))
        (testing "a pool is refused: there is no commit to wait for"
          (is (= :cellar/not-a-transaction
                 (:type (ex-data (try (cellar/run-after-commit! pool #(swap! ran conj :never)) (catch Exception e e)))))))
        (finally (cellar/stop-pool! pool))))))
