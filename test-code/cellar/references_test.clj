(ns cellar.references-test
  "References over several columns and declared triggers. The Postgres
   tests run when CELLAR_TEST_PORT is set; they say they were skipped
   otherwise. Each starts from an empty database."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [cellar.api :as cellar]
   [cellar.test-settings :as test-settings]))

(def ^:private port test-settings/port)

(def ^:private shops
  {:table      :shops
   :columns    [[:shop/id :uuid {:primary-key? true :default :random-uuid}]]
   :indexes    []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(def ^:private orders
  {:table      :orders
   :columns    [[:order/id      :uuid {:primary-key? true :default :random-uuid}]
                [:order/shop-id :uuid {:references :shop/id}]]
   :indexes    []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(def ^:private fill-trigger
  {:function "public.fill_job" :arguments [] :timing :before :events #{:insert :update}
   :update-of [:job/order-id] :for-each :row})

(def ^:private jobs
  {:table      :jobs
   :columns    [[:job/id       :uuid {:primary-key? true :default :random-uuid}]
                [:job/order-id :uuid {:references :order/id}]
                [:job/shop-id  :uuid]]
   :indexes    [[:job/order-id]]
   :references [{:columns [:job/order-id :job/shop-id] :to [:order/id :order/shop-id] :on-update :cascade}]
   :triggers   [fill-trigger]
   :gates      nil
   :on-change  nil})

(defn- run-sql! [pool text]
  (cellar/run-statement! pool (cellar/make-query-from-sql-text "test" text) {}))

(defn- find-rows [pool text]
  (cellar/find-rows pool (cellar/make-query-from-sql-text "test" text) {}))

(def ^:private create-fill-function
  (str "CREATE OR REPLACE FUNCTION public.fill_job() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
       "SELECT order__shop_id INTO NEW.job__shop_id FROM orders WHERE order__id = NEW.job__order_id; RETURN NEW; END $$"))

(defn- with-empty-database [f]
  (let [pool (test-settings/start-pool! 4 10000)]
    (try
      (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS cellar CASCADE")
      (run-sql! pool create-fill-function)
      (f pool)
      (finally (cellar/stop-pool! pool)))))

(defn- migrate-and-wait! [pool tables]
  @(:slow-part (cellar/migrate! pool tables [] test-settings/migrate-options)))

(defn- plan-of [pool tables]
  (select-keys (cellar/make-plan-from-tables pool tables []) [:instant :slow :stops :changes-to-mark]))

(def ^:private nothing-to-do {:instant [] :slow [] :stops [] :changes-to-mark []})

(deftest a-reference-over-several-columns-names-its-targets-primary-key
  (is (= :cellar/bad-reference
         (:type (ex-data (try (cellar/make-plan-from-tables nil [shops orders (assoc-in jobs [:references 0 :to] [:order/shop-id :order/id :order/shop-id])] [])
                              (catch Exception e e)))))
      "columns and :to pair up")
  (let [shopless (assoc jobs :references [{:columns [:job/shop-id :job/shop-id] :to [:order/shop-id :order/shop-id] :on-update :cascade}])]
    (is (re-find #"primary key among :to"
                 (ex-message (try (cellar/make-plan-from-tables nil [shops orders shopless] []) (catch Exception e e))))))
  (is (= :cellar/bad-tables
         (:type (ex-data (try (cellar/make-plan-from-tables nil [shops orders (assoc-in jobs [:references 0 :columns] [:job/order-id])] [])
                              (catch Exception e e)))))
      "a one-column reference is the column's :references"))

(deftest a-triggers-update-of-names-its-columns-with-update
  (is (= :cellar/bad-trigger
         (:type (ex-data (try (cellar/make-plan-from-tables nil [shops orders (assoc-in jobs [:triggers 0 :events] #{:insert})] [])
                              (catch Exception e e))))))
  (is (= :cellar/bad-trigger
         (:type (ex-data (try (cellar/make-plan-from-tables nil [shops orders (assoc-in jobs [:triggers 0 :update-of] [:job/nothing])] [])
                              (catch Exception e e)))))))

(deftest a-fresh-database-gets-the-reference-its-unique-key-and-the-trigger
  (if-not port
    (println "cellar.references-test: skipped, CELLAR_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (migrate-and-wait! pool [shops orders jobs])
        (is (= nothing-to-do (plan-of pool [shops orders jobs])))
        (let [[shop-a shop-b] (map :shop/id (find-rows pool "INSERT INTO shops (shop__id) VALUES (DEFAULT), (DEFAULT) RETURNING shop__id"))
              order (:order/id (first (find-rows pool (str "INSERT INTO orders (order__shop_id) VALUES ('" shop-a "') RETURNING order__id"))))]
          (testing "the trigger fills the carried column on insert"
            (run-sql! pool (str "INSERT INTO jobs (job__order_id) VALUES ('" order "')"))
            (is (= [shop-a] (map :job/shop-id (find-rows pool "SELECT job__shop_id FROM jobs")))))
          (testing "a change of the parent's columns is carried down"
            (run-sql! pool (str "UPDATE orders SET order__shop_id = '" shop-b "'"))
            (is (= [shop-b] (map :job/shop-id (find-rows pool "SELECT job__shop_id FROM jobs")))))
          (testing "a child can't say another value than its parent's"
            (is (thrown? Exception (run-sql! pool (str "UPDATE jobs SET job__shop_id = '" shop-a "'")))))
          (testing "a parent with children can't be deleted"
            (is (thrown? Exception (run-sql! pool "DELETE FROM orders")))))))))

(deftest a-reference-added-to-tables-with-rows-waits-for-its-unique-key
  (if-not port
    (println "cellar.references-test: skipped, CELLAR_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (let [plain-jobs (assoc jobs :references [] :triggers [])]
          (migrate-and-wait! pool [shops orders plain-jobs])
          (run-sql! pool "INSERT INTO shops DEFAULT VALUES")
          (run-sql! pool "INSERT INTO orders (order__shop_id) SELECT shop__id FROM shops")
          (run-sql! pool "INSERT INTO jobs (job__order_id, job__shop_id) SELECT order__id, order__shop_id FROM orders")
          (testing "the unique key is built in the slow part, the reference made and proven after it"
            (let [{:keys [instant slow stops]} (plan-of pool [shops orders jobs])
                  slow-sql (mapv :sql slow)]
              (is (= [] stops))
              (is (= ["CREATE TRIGGER"] (map #(subs (:sql %) 0 14) instant)) "only the trigger is instant")
              (is (re-find #"CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS orders_order__id_order__shop_id_key ON orders \(order__id, order__shop_id\)"
                           (first slow-sql)))
              (is (re-find #"ADD CONSTRAINT jobs_job__order_id_job__shop_id_fkey FOREIGN KEY \(job__order_id, job__shop_id\) REFERENCES orders \(order__id, order__shop_id\) ON UPDATE CASCADE NOT VALID"
                           (nth slow-sql 2)))
              (is (str/starts-with? (nth slow-sql 3) "ALTER TABLE jobs VALIDATE CONSTRAINT"))))
          (migrate-and-wait! pool [shops orders jobs])
          (is (= nothing-to-do (plan-of pool [shops orders jobs])))
          (testing "another ON UPDATE: the reference is made again"
            (let [refusing (assoc-in jobs [:references 0 :on-update] :refuse)
                  {:keys [instant slow]} (plan-of pool [shops orders refusing])]
              (is (= ["ALTER TABLE jobs DROP CONSTRAINT jobs_job__order_id_job__shop_id_fkey"
                      "ALTER TABLE jobs ADD CONSTRAINT jobs_job__order_id_job__shop_id_fkey FOREIGN KEY (job__order_id, job__shop_id) REFERENCES orders (order__id, order__shop_id) NOT VALID"]
                     (map :sql instant)))
              (is (= 1 (count slow)))
              (migrate-and-wait! pool [shops orders refusing])
              (is (= nothing-to-do (plan-of pool [shops orders refusing])))))
          (testing "a changed trigger is made again; a reference in no map goes before its unique key"
            (let [changed (assoc jobs :references [] :triggers [(assoc fill-trigger :update-of nil)])
                  sqls    (map :sql (:instant (plan-of pool [shops orders changed])))]
              (is (= ["ALTER TABLE jobs DROP CONSTRAINT jobs_job__order_id_job__shop_id_fkey"
                      "ALTER TABLE orders DROP CONSTRAINT orders_order__id_order__shop_id_key"]
                     (filter #(str/includes? % "DROP CONSTRAINT") sqls)))
              (is (= 1 (count (filter #(str/starts-with? % "DROP TRIGGER") sqls))))
              (is (= 1 (count (filter #(str/starts-with? % "CREATE TRIGGER") sqls))))
              (migrate-and-wait! pool [shops orders changed])
              (is (= nothing-to-do (plan-of pool [shops orders changed])))))
          (testing "no trigger in the map: it goes"
            (migrate-and-wait! pool [shops orders plain-jobs])
            (is (= [] (find-rows pool "SELECT tgname FROM pg_trigger WHERE tgrelid = 'jobs'::regclass AND NOT tgisinternal")))))))))

(deftest a-renamed-column-keeps-the-references-names
  (if-not port
    (println "cellar.references-test: skipped, CELLAR_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (migrate-and-wait! pool [shops orders jobs])
        (let [renamed (-> jobs
                          (assoc :columns [[:job/id :uuid {:primary-key? true :default :random-uuid}]
                                           [:job/parent-id :uuid {:references :order/id}]
                                           [:job/shop-id :uuid]])
                          (assoc :indexes [[:job/parent-id]])
                          (assoc-in [:references 0 :columns] [:job/parent-id :job/shop-id])
                          (assoc-in [:triggers 0 :update-of] [:job/parent-id]))
              changes [{:id "r1" :rename-column [:jobs :job/order-id :job/parent-id]}]
              plan    (cellar/make-plan-from-tables pool [shops orders renamed] changes)]
          (is (= [] (:stops plan)))
          (is (some #(= "ALTER TABLE jobs RENAME CONSTRAINT jobs_job__order_id_job__shop_id_fkey TO jobs_job__parent_id_job__shop_id_fkey" (:sql %))
                    (:instant plan)))
          @(:slow-part (cellar/migrate! pool [shops orders renamed] changes test-settings/migrate-options))
          (is (= {:instant [] :slow [] :stops [] :changes-to-mark []}
                 (select-keys (cellar/make-plan-from-tables pool [shops orders renamed] changes) [:instant :slow :stops :changes-to-mark]))))))))

(deftest is-transaction-tells-a-transaction-from-a-pool
  (if-not port
    (println "cellar.references-test: skipped, CELLAR_TEST_PORT is not set")
    (let [pool (test-settings/start-pool! 1 10000)]
      (try
        (is (false? (cellar/is-transaction? pool)))
        (is (true? (cellar/run-in-transaction! pool {:read-only? true} cellar/is-transaction?)))
        (finally (cellar/stop-pool! pool))))))
