(ns cellar.params-test
  (:require
   [clojure.test :refer [deftest is]]
   [cellar.queries.params :as params]))

(deftest a-param-becomes-a-question-mark
  (is (= {:text-with-question-marks "SELECT order__id FROM orders WHERE order__shop_id = ? AND x::int > ? AND note = ':not/a-param'"
          :text-with-dollar-numbers "SELECT order__id FROM orders WHERE order__shop_id = $1 AND x::int > $2 AND note = ':not/a-param'"
          :param-names              [:order/shop-id :limit]}
         (params/turn-sql-text-into-statement
          "SELECT order__id FROM orders WHERE order__shop_id = :order/shop-id AND x::int > :limit AND note = ':not/a-param'"))))

(deftest a-missing-param-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing param :limit for a.sql"
                        (params/list-param-values {:param-names [:limit]} {} "a.sql"))))
