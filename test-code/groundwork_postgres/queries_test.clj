(ns groundwork-postgres.queries-test
  "Runs against a real Postgres when POSTGRES_TEST_PORT is set (on
   localhost, user from POSTGRES_TEST_USER, password from POSTGRES_TEST_PASSWORD, no TLS);
   says it was skipped otherwise."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork-postgres.api :as sql]
   [groundwork-postgres.test-settings :as test-settings])
  (:import
   (java.time Instant OffsetDateTime)))

(def ^:private port test-settings/port)

(def ^:private find-orders-of-shop (sql/make-query-from-sql-file "groundwork_postgres/find_orders_of_shop.sql"))
(def ^:private insert-order (sql/make-query-from-sql-file "groundwork_postgres/insert_order.sql"))

(def ^:private create-test-orders (sql/make-query-from-sql-file "groundwork_postgres/create_test_orders.sql"))
(def ^:private drop-test-orders (sql/make-query-from-sql-file "groundwork_postgres/drop_test_orders.sql"))

(deftest queries-against-postgres
  (if-not port
    (println "groundwork-postgres.queries-test: skipped, POSTGRES_TEST_PORT is not set")
    (let [pool (test-settings/start-pool! 2 5000)]
      (try
        (sql/run-statement! pool drop-test-orders {})
        (sql/run-statement! pool create-test-orders {})
        (testing "a statement says how many rows it changed"
          (is (= 1 (sql/run-statement! pool insert-order {:order/id 42 :order/shop-id 7 :order/total 120.50M
                                                          :order/at (Instant/parse "2026-10-22T10:00:00Z")}))))
        (testing "rows come back with keys from the columns, and real types"
          (let [[order] (sql/find-rows pool find-orders-of-shop {:order/shop-id 7})]
            (is (= {:order/id 42 :order/total 120.50M} (dissoc order :order/at)))
            (is (instance? OffsetDateTime (:order/at order)))))
        (testing "no rows is an empty vector; one row is the row"
          (is (= [] (sql/find-rows pool find-orders-of-shop {:order/shop-id 8})))
          (is (= 42 (:order/id (sql/find-one-row pool find-orders-of-shop {:order/shop-id 7})))))
        (testing "a transaction that throws keeps nothing"
          (is (thrown? clojure.lang.ExceptionInfo
                       (sql/run-in-transaction! pool {:read-only? false :log test-settings/log}
                         (fn [transaction]
                           (sql/run-statement! transaction insert-order {:order/id 43 :order/shop-id 7 :order/total 9.50M :order/at nil})
                           (throw (ex-info "no" {}))))))
          (is (= 1 (count (sql/find-rows pool find-orders-of-shop {:order/shop-id 7})))))
        (testing "a second row throws in find-one-row"
          (sql/run-statement! pool insert-order {:order/id 43 :order/shop-id 7 :order/total 9.50M :order/at nil})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"more than one row"
                                (sql/find-one-row pool find-orders-of-shop {:order/shop-id 7}))))
        (finally
          (sql/run-statement! pool drop-test-orders {})
          (sql/stop-pool! pool))))))
