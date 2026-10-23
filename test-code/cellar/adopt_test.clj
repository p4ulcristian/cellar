(ns cellar.adopt-test
  "cellar adopts what Postgres has under other names, the same shape: a
   rename, never a rebuild. Runs when CELLAR_TEST_PORT is set; each test
   starts from an empty database."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [cellar.api :as cellar]
   [cellar.test-settings :as test-settings]))

(def ^:private port test-settings/port)

(defn- run-sql! [pool text]
  (cellar/run-statement! pool (cellar/make-query-from-sql-text "test" text) {}))

(defn- find-rows [pool text]
  (cellar/find-rows pool (cellar/make-query-from-sql-text "test" text) {}))

(defn- with-empty-database [f]
  (let [pool (test-settings/start-pool! 4 10000)]
    (try
      (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS cellar CASCADE")
      (f pool)
      (finally (cellar/stop-pool! pool)))))

(defn- plan-of [pool tables changes]
  (select-keys (cellar/make-plan-from-tables pool (test-settings/declare-tables tables) changes) [:instant :slow :stops :changes-to-mark]))

(defn- migrate-and-wait! [pool tables changes]
  @(:slow-part (cellar/migrate! pool (test-settings/declare-tables tables) changes test-settings/migrate-options)))

(defn find-oids
  "Each named constraint's and index's oid: the same oid after a migrate
   is the same object, renamed, not made again."
  [pool]
  (into {} (map (juxt :name/text :oid/text))
        (find-rows pool (str "SELECT conname::text AS name__text, oid::text AS oid__text FROM pg_constraint "
                             "WHERE connamespace = 'public'::regnamespace AND contype <> 'n' "
                             "UNION ALL SELECT relname::text, oid::text FROM pg_class "
                             "WHERE relnamespace = 'public'::regnamespace AND relkind = 'i'"))))

(def ^:private shops
  {:table      :shops
   :columns    [[:shop/id :uuid {:primary-key? true :default :random-uuid}]]
   :indexes    []
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(def ^:private orders
  {:table      :orders
   :columns    [[:order/id      :uuid {:primary-key? true :default :random-uuid}]
                [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
                [:order/status  [:enum "draft" "done"] {:default "draft"}]
                [:order/total   :decimal {:default 0}]]
   :indexes    [{:columns [[:order/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}]
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(def ^:private made-by-hand
  ;; as another tool made them: its own names, its own way of writing SQL
  (str "CREATE TABLE shops (shop__id uuid PRIMARY KEY DEFAULT gen_random_uuid());"
       "CREATE TABLE orders (order__id uuid PRIMARY KEY DEFAULT gen_random_uuid(),"
       " order__shop_id uuid NOT NULL CONSTRAINT orders_shop_fk REFERENCES shops,"
       " order__state text NOT NULL DEFAULT 'draft' CONSTRAINT orders_state_check CHECK (order__state = ANY (ARRAY['draft', 'done'])),"
       " order__total numeric NOT NULL DEFAULT 0);"
       "CREATE INDEX orders_by_shop ON orders (order__shop_id);"
       "INSERT INTO shops DEFAULT VALUES; INSERT INTO orders (order__shop_id) SELECT shop__id FROM shops"))

(deftest what-postgres-has-under-other-names-is-renamed
  (if-not port
    (println "cellar.adopt-test: skipped, CELLAR_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (run-sql! pool made-by-hand)
        (let [before  (find-oids pool)
              changes [{:id "a1" :rename-column [:orders :order/state :order/status]}]
              {:keys [instant slow stops]} (plan-of pool [shops orders] changes)]
          (testing "the key, the check and the index are renamed, nothing else; the check through the column's rename"
            (is (= [] stops))
            (is (= ["ALTER TABLE orders RENAME COLUMN order__state TO order__status"
                    "ALTER TABLE orders RENAME CONSTRAINT orders_shop_fk TO orders_order__shop_id_fkey"
                    "ALTER TABLE orders RENAME CONSTRAINT orders_state_check TO orders_order__status_enum"
                    "ALTER INDEX orders_by_shop RENAME TO orders_order__shop_id_idx"]
                   (map :sql instant)))
            (is (= [] slow) "no key proven again, no index built"))
          (migrate-and-wait! pool [shops orders] changes)
          (testing "the same objects, by oid"
            (let [after (find-oids pool)]
              (is (= (get before "orders_shop_fk") (get after "orders_order__shop_id_fkey")))
              (is (= (get before "orders_state_check") (get after "orders_order__status_enum")))
              (is (= (get before "orders_by_shop") (get after "orders_order__shop_id_idx")))))
          (is (= {:instant [] :slow [] :stops [] :changes-to-mark []} (plan-of pool [shops orders] changes))))))))

(deftest a-different-piece-is-made-again
  (if-not port
    (println "cellar.adopt-test: skipped, CELLAR_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (run-sql! pool (str/replace made-by-hand "ARRAY['draft', 'done']" "ARRAY['draft', 'done', 'lost']"))
        (let [{:keys [instant slow]} (plan-of pool [shops orders] [{:id "a1" :rename-column [:orders :order/state :order/status]}])]
          (testing "a check that allows another value isn't adopted: the map's is added NOT VALID and proven in the slow part"
            (is (some #(= "ALTER TABLE orders ADD CONSTRAINT orders_order__status_enum CHECK (order__status IN ('draft', 'done')) NOT VALID" %)
                      (map :sql instant)))
            (is (= ["ALTER TABLE orders VALIDATE CONSTRAINT orders_order__status_enum"] (map :sql slow)))
            (is (= "ALTER TABLE orders DROP CONSTRAINT orders_state_check" (last (map :sql instant)))
                "and the one no map names is dropped")))))))

(deftest a-default-is-compared-as-postgres-prints-it
  (if-not port
    (println "cellar.adopt-test: skipped, CELLAR_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (migrate-and-wait! pool [shops orders] [])
        (is (= {:instant [] :slow [] :stops [] :changes-to-mark []} (plan-of pool [shops orders] [])))
        (let [other (assoc-in orders [:columns 3] [:order/total :decimal {:default -1.5}])]
          (is (= ["ALTER TABLE orders ALTER COLUMN order__total SET DEFAULT (-1.5)"]
                 (map :sql (:instant (plan-of pool [shops other] []))))))))))
