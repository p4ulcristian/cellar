(ns groundwork-postgres.types-test
  "A column's type states Postgres's limit in malli's words. The Postgres
   tests run when POSTGRES_TEST_PORT is set; each starts from an empty
   database."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork-postgres.api :as postgres]
   [groundwork-postgres.test-settings :as test-settings]
   [groundwork-shaped-json.api :as shaped-json]))

(def ^:private port test-settings/port)

(defn- run-sql! [pool text]
  (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- find-rows [pool text params]
  (postgres/find-rows pool (postgres/make-query-from-sql-text "test" text) params))

(defn- with-empty-database [f]
  (let [pool (test-settings/start-pool! 4 10000)]
    (try
      (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
      (f pool)
      (finally (postgres/stop-pool! pool)))))

(defn- plan-of [pool tables]
  (select-keys (postgres/make-plan-from-tables pool (test-settings/declare-tables tables) []) [:instant :slow :stops :changes-to-mark]))

(def ^:private nothing-to-do {:instant [] :slow [] :stops [] :changes-to-mark []})

(def ^:private jobs
  {:table      :jobs
   :columns    [[:job/id       :uuid {:primary-key? true :default :random-uuid}]
                [:job/status   [:enum {:max 10} "queued" "running" "done"] {:default "queued"}]
                [:job/kind     [:string {:max 10}]]
                [:job/height   [:int {:min -2147483648 :max 2147483647}]]
                [:job/year     [:int {:min -32768 :max 32767}] {:optional? true}]
                [:job/min-qty  [:decimal {:precision 10 :scale 2}]]
                [:job/flags    [:vector [:string {:max 64}]]]
                [:job/counts   [:vector :int]]
                [:job/slots    [:json [:vector [:map [:slot/day :time/local-date]]]]]]
   :indexes    []
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(deftest each-type-is-the-postgres-type-it-names
  (let [refusal (fn [type] (:type (ex-data (try (postgres/make-plan-from-tables nil (test-settings/declare-tables [(assoc-in jobs [:columns 2 1] type)]) [])
                                                (catch Exception e e)))))]
    (doseq [type [[:string {:min 1}] [:int {:min 0}] [:int {:min 0 :max 10}] [:decimal {:precision 10}]
                  [:decimal {:precision 2 :scale 3}] [:enum {:max 2} "abc"] [:vector [:enum "a"]] [:vector [:json :any]]
                  [:json :any :any] :keyword [:map [:a :int]]]]
      (is (= :groundwork-postgres/unknown-type (refusal type)) (pr-str type))))
  (if-not port
    (println "groundwork-postgres.types-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        @(:slow-part (postgres/migrate! pool (test-settings/declare-tables [jobs]) [] test-settings/migrate-options))
        (is (= nothing-to-do (plan-of pool [jobs])))
        (is (= [["job__id" "uuid"] ["job__status" "character varying(10)"] ["job__kind" "character varying(10)"]
                ["job__height" "integer"] ["job__year" "smallint"] ["job__min_qty" "numeric(10,2)"]
                ["job__flags" "character varying(64)[]"] ["job__counts" "bigint[]"] ["job__slots" "jsonb"]]
               (map (juxt :column/name :column/type)
                    (find-rows pool (str "SELECT attname::text AS column__name, format_type(atttypid, atttypmod) AS column__type "
                                         "FROM pg_attribute WHERE attrelid = 'jobs'::regclass AND attnum > 0 ORDER BY attnum")
                               {}))))
        (testing "a value of each type goes in and comes back"
          (run-sql! pool (str "INSERT INTO jobs (job__kind, job__height, job__year, job__min_qty, job__flags, job__counts, job__slots) "
                              "VALUES ('coat', 2147483647, -32768, 12345678.99, '{rush,\"big one\"}', '{1,9007199254740993}', "
                              "'[{\"slot__day\": \"2026-11-15\"}]')"))
          (is (= [{:job/status "queued" :job/kind "coat" :job/height 2147483647 :job/year -32768 :job/min-qty 12345678.99M
                   :job/flags ["rush" "big one"] :job/counts [1 9007199254740993] :job/slots [{:slot/day "2026-11-15"}]}]
                 (find-rows pool (str "SELECT job__status, job__kind, job__height, job__year, job__min_qty, job__flags, job__counts, job__slots "
                                      "FROM jobs") {}))))
        (testing "Postgres holds the limits the types state"
          (is (thrown? Exception (run-sql! pool "UPDATE jobs SET job__kind = 'longer than ten'")))
          (is (thrown? Exception (run-sql! pool "UPDATE jobs SET job__height = 2147483648")))
          (is (thrown? Exception (run-sql! pool "UPDATE jobs SET job__min_qty = 123456789")))
          (is (thrown? Exception (run-sql! pool "UPDATE jobs SET job__status = 'lost'"))))))))

(deftest columns-another-tool-made-are-taken-as-they-are
  (if-not port
    (println "groundwork-postgres.types-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (run-sql! pool (str "CREATE TABLE jobs (job__id uuid PRIMARY KEY DEFAULT gen_random_uuid(),"
                            " job__status VARCHAR(10) NOT NULL DEFAULT 'queued' CONSTRAINT jobs_status_check CHECK (job__status IN ('queued', 'running', 'done')),"
                            " job__kind VARCHAR(10) NOT NULL, job__height INTEGER NOT NULL, job__year SMALLINT,"
                            " job__min_qty DECIMAL(10,2) NOT NULL, job__flags varchar(64)[] NOT NULL, job__counts bigint[] NOT NULL,"
                            " job__slots jsonb NOT NULL)"))
        (is (= {:instant ["ALTER TABLE jobs RENAME CONSTRAINT jobs_status_check TO jobs_job__status_enum"] :slow [] :stops []}
               (update (select-keys (plan-of pool [jobs]) [:instant :slow :stops]) :instant #(map :sql %)))
            "no type changes, the varchar enum's check adopted")))))

(def ^:private test-shaper
  (shaped-json/make-shaper-from-settings
   {:most-shapes-kept 1000
    :json-limits      {:max-nesting-depth 1000 :max-number-length 1000 :max-string-length 20000000
                       :max-name-length 50000 :max-document-length nil :max-token-count nil
                       :max-writing-nesting-depth 1000}}))

(deftest the-row-shape-refuses-what-postgres-would
  (let [row-shape (postgres/turn-table-into-row-shape jobs)
        row       {:job/id "7f3c1e2a-0000-4000-8000-000000000001" :job/status "queued" :job/kind "coat"
                   :job/height 1 :job/min-qty "12.30" :job/flags ["rush"] :job/counts [1]
                   :job/slots [{:slot/day "2026-11-15"}]}
        way-in    #(shaped-json/turn-json-data-into-shaped-data-or-errors test-shaper row-shape %)]
    (is (contains? (way-in row) :shaped-data))
    (is (= {:job/kind ["should be at most 10 characters"]
            :job/height ["should be between -2147483648 and 2147483647"]
            :job/min-qty ["should be a decimal with at most 8 digits before the point and 2 after"]
            :job/flags [["should be at most 64 characters"]]
            :job/slots [{:slot/day ["should be a day, like 2026-11-15"]}]}
           (:errors (way-in (assoc row :job/kind "longer than ten" :job/height 2147483648 :job/min-qty "12.345"
                                   :job/flags [(apply str (repeat 65 "x"))] :job/slots [{:slot/day "soon"}])))))))

(def ^:private sheets
  {:table      :price-sheets
   :columns    [[:price-sheet/id       :uuid {:primary-key? true :default :random-uuid}]
                [:price-sheet/currency :string]]
   :indexes    []
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(deftest a-column-changes-type-by-a-change
  (if-not port
    (println "groundwork-postgres.types-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (let [declared (test-settings/declare-tables [sheets])
              plan     #(select-keys (postgres/make-plan-from-tables pool declared %) [:instant :slow :stops :changes-to-mark])
              change   {:id "t1" :change-column-type :price-sheet/currency :from "character(3)"}]
          @(:slow-part (postgres/migrate! pool declared [] test-settings/migrate-options))
          (run-sql! pool "ALTER TABLE price_sheets ALTER COLUMN price_sheet__currency TYPE character(3)")
          (run-sql! pool "INSERT INTO price_sheets (price_sheet__currency) VALUES ('EUR'), ('HU ')")
          (testing "without the change: a stop that says the change to write"
            (is (= ["price_sheets.price_sheet__currency is character(3) in Postgres but text in its map: add a change, {:change-column-type <its key> :from \"character(3)\"}"]
                   (:stops (plan [])))))
          (testing ":from must be what Postgres has"
            (is (= "change t1: price_sheets.price_sheet__currency is character(3) in Postgres, not character(2) as its :from says"
                   (first (:stops (plan [(assoc change :from "character(2)")]))))))
          (testing "the change: the type in the slow part, then marked done there"
            (is (= {:instant         []
                    :slow            ["ALTER TABLE price_sheets ALTER COLUMN price_sheet__currency TYPE text USING price_sheet__currency::text"
                                      "INSERT INTO groundwork_postgres.changes_done (change_done__id) VALUES ('t1') ON CONFLICT (change_done__id) DO NOTHING"]
                    :stops           []
                    :changes-to-mark []}
                   (update (plan [change]) :slow (partial mapv :sql))))
            (is (postgres/does-database-match-tables? pool declared [change]) "the app may start: only a slow part is left")
            @(:slow-part (postgres/migrate! pool declared [change] test-settings/migrate-options))
            (is (= nothing-to-do (plan [change])) "and then nothing differs, the change done")
            (is (= ["EUR" "HU"] (map :price-sheet/currency (find-rows pool "SELECT price_sheet__currency FROM price_sheets ORDER BY 1" {})))
                "the rows kept, character's padding gone as Postgres casts it")))))))

(deftest a-type-change-a-view-reads-is-a-stop
  (if-not port
    (println "groundwork-postgres.types-test: skipped, POSTGRES_TEST_PORT is not set")
    (with-empty-database
      (fn [pool]
        (let [declared (assoc (test-settings/declare-tables [sheets]) :views ["groundwork_postgres/objects/currencies.sql"])]
          @(:slow-part (postgres/migrate! pool (assoc declared :views []) [] test-settings/migrate-options))
          (run-sql! pool "ALTER TABLE price_sheets ALTER COLUMN price_sheet__currency TYPE character(3)")
          (run-sql! pool "CREATE VIEW currencies AS SELECT DISTINCT price_sheet__currency FROM price_sheets")
          (is (= ["change t1: price_sheets.price_sheet__currency is read by the views currencies, which Postgres keeps from changing its type: take them out of :views for this deploy"]
                 (:stops (postgres/make-plan-from-tables pool declared [{:id "t1" :change-column-type :price-sheet/currency :from "character(3)"}])))))))))
