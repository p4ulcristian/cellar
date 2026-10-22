(ns cellar.tables-read-test
  "Which tables a question read, hearing NOTIFY, and change triggers. Runs
   when CELLAR_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [cellar.api :as database]
   [cellar.test-settings :as test-settings :refer [column]]))

(def ^:private port test-settings/port)

(defn- start-pool! [] (test-settings/start-pool! 3 5000))

(defn- run-sql! [pool text] (database/run-statement! pool (database/make-query-from-sql-text "test" text) {}))

(def ^:private find-things-named
  (database/make-query-from-sql-text "things named" "SELECT thing__id FROM things WHERE thing__name = :thing/name"))
(def ^:private find-named-things
  (database/make-query-from-sql-text "a view" "SELECT thing__id FROM named_things"))
(def ^:private count-things
  (database/make-query-from-sql-text "a function" "SELECT count_things() AS n"))
(def ^:private count-things-saying-so
  (database/make-query-from-sql-text "a function, named" "-- reads: things\nSELECT count_things() AS n"))
(def ^:private find-untyped
  (database/make-query-from-sql-text "a param Postgres can't type" "SELECT :x IS NULL AS b"))

(deftest a-key-from-a-table-name
  (is (= :orders (database/turn-table-name-into-table-key "public" "orders")))
  (is (= :billing/invoices (database/turn-table-name-into-table-key "billing" "invoices"))))

(deftest which-tables-a-question-read
  (if-not port
    (println "cellar.tables-read-test: skipped, CELLAR_TEST_PORT is not set")
    (let [pool (start-pool!)]
      (try
        (run-sql! pool (str "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;"
                            "CREATE TABLE things (thing__id int PRIMARY KEY, thing__name text);"
                            "CREATE VIEW named_things AS SELECT * FROM things WHERE thing__name IS NOT NULL;"
                            "CREATE FUNCTION count_things() RETURNS bigint LANGUAGE sql AS 'SELECT count(*) FROM things'"))
        (testing "a query: its tables, planned without running it"
          (is (= [[] #{:things}] (database/run-and-list-tables-read #(database/find-rows pool find-things-named {:thing/name "a"}))))
          (is (= [nil #{:things}] (database/run-and-list-tables-read #(database/find-one-row pool find-things-named {:thing/name "a"})))))
        (testing "a view counts as the tables under it"
          (is (= #{:things} (second (database/run-and-list-tables-read #(database/find-rows pool find-named-things {}))))))
        (testing "a function hides its tables; the query names them in a comment"
          (is (= #{} (second (database/run-and-list-tables-read #(database/find-rows pool count-things {})))))
          (is (= #{:things} (second (database/run-and-list-tables-read #(database/find-rows pool count-things-saying-so {}))))))
        (testing "a query Postgres can't prepare without values is unknown, and the transaction it was in goes on"
          (is (= [1 :unknown]
                 (database/run-and-list-tables-read
                  #(database/run-in-transaction! pool {:read-only? false}
                     (fn [transaction]
                       (database/find-one-row transaction find-untyped {:x "a"})
                       (run-sql! transaction "INSERT INTO things VALUES (1, 'a')"))))))
          (is (= [{:thing/id 1}] (database/find-rows pool find-things-named {:thing/name "a"}))))
        (testing "outside run-and-list-tables-read nothing is noted"
          (is (= [{:thing/id 1}] (database/find-rows pool find-things-named {:thing/name "a"}))))
        (finally (database/stop-pool! pool))))))

(deftest hearing-notify
  (if-not port
    (println "cellar.tables-read-test: skipped, CELLAR_TEST_PORT is not set")
    (let [pool     (start-pool!)
          heard    (promise)
          listener (database/start-listening! pool "cellar_test" {:on-notifications       #(deliver heard %)
                                                                      :on-reconnect           (fn [])
                                                                      :batch-window-ms        50
                                                                      :check-stopped-every-ms 500
                                                                      :reconnect-after-ms     1000
                                                                      :stop-wait-ms           2000})]
      (try
        (Thread/sleep 300)
        (run-sql! pool "SELECT pg_notify('cellar_test', 'one'), pg_notify('cellar_test', 'two')")
        (is (= [{:channel "cellar_test" :payload "one"} {:channel "cellar_test" :payload "two"}]
               (deref heard 3000 :nothing))
            "two notifications close together: one batch")
        (finally
          (is (database/stop-listening! listener) "the listener had ended")
          (database/stop-pool! pool))))))

(def ^:private told-things
  {:table     :told-things
   :columns   [(column :told-thing/id :int :primary-key? true)]
   :indexes   []
   :gates     nil
   :on-change {:function "public.tell_test" :arguments ["told_thing__id"]}})

(deftest change-triggers
  (if-not port
    (println "cellar.tables-read-test: skipped, CELLAR_TEST_PORT is not set")
    (let [pool (start-pool!)]
      (try
        (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS cellar CASCADE")
        (run-sql! pool "CREATE FUNCTION public.tell_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$")
        (let [plan (database/make-plan-from-tables pool [told-things] [])]
          (is (= 3 (count (filter #(str/starts-with? (:sql %) "CREATE TRIGGER") (:instant plan))))
              "one trigger per insert, update and delete"))
        (with-out-str @(:slow-part (database/migrate! pool [told-things] [] test-settings/migrate-options)))
        (is (database/does-database-match-tables? pool [told-things] []))
        (testing "a changed :on-change is new triggers in place of the old"
          (let [changed (assoc-in told-things [:on-change :arguments] ["told_thing__id" "x"])
                plan    (database/make-plan-from-tables pool [changed] [])]
            (is (= 3 (count (filter #(str/starts-with? (:sql %) "DROP TRIGGER") (:instant plan)))))
            (is (= 3 (count (filter #(str/starts-with? (:sql %) "CREATE TRIGGER") (:instant plan)))))))
        (testing "no :on-change: the triggers go"
          (is (= 3 (count (filter #(str/starts-with? (:sql %) "DROP TRIGGER")
                                  (:instant (database/make-plan-from-tables pool [(assoc told-things :on-change nil)] [])))))))
        (finally (database/stop-pool! pool))))))
