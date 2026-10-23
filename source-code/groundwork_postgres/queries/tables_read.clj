(ns groundwork-postgres.queries.tables-read
  "Which tables a query reads, asked of Postgres's planner once per query,
   and noted while a function runs: what a question's answer was read from.

     (run-and-list-tables-read #(find-rows pool find-orders-of-shop {:order/shop-id 7}))
     ;=> [[{:order/id …}] #{:orders}]

   The plan is a generic one (one that doesn't depend on the params'
   values), made without running the query. A view counts as the tables
   under it. A query Postgres can't prepare is unknown, and so is every run
   that used it: whoever kept the answer must treat any change as one for
   it.

   A function that reads tables inside hides them from the planner; the
   query names them in a comment line, as table keys:

     -- reads: orders shops
     SELECT count_open_orders(:shop/id) AS n"
  (:require
   [clojure.string :as str]
   [groundwork-postgres.queries.names :as names]
   [jsonista.core :as json])
  (:import
   (java.sql Connection Savepoint Statement)))

(def ^:dynamic ^:private *noted*
  "While run-and-list-tables-read runs, {:tables an-atom-of-the-tables-read
   :log the-log-it-was-given}; nil otherwise."
  nil)

(defn- collect-tables [node]
  (let [here     (when-let [table (get node "Relation Name")]
                   (or (names/find-key-for-table (get node "Schema") table) :unknown))
        children (mapcat collect-tables (get node "Plans"))]
    (cond-> (set children) here (conj here))))

(defn- run-quietly! [^Statement statement sql-text]
  (try (.execute statement sql-text) (catch Exception _ nil)))

(defn- ask-planner
  "Prepares the query under a name of its own, asks Postgres to explain its
   generic plan (the one that doesn't depend on the params' values; NULLs
   stand in for them), and forgets it. Plain statements, so the $1… stay
   the prepared query's own."
  [^Connection connection query]
  (with-open [statement (.createStatement connection)]
    (try
      (.execute statement (str "PREPARE groundwork_postgres_plan AS " (:text-with-dollar-numbers query)))
      (.execute statement "SET plan_cache_mode = force_generic_plan")
      (with-open [result (.executeQuery statement
                                        (str "EXPLAIN (VERBOSE, FORMAT JSON) EXECUTE groundwork_postgres_plan"
                                             (when-let [names (seq (:param-names query))]
                                               (str "(" (str/join ", " (repeat (count names) "NULL")) ")"))))]
        (.next result)
        (let [tables (collect-tables (get (first (json/read-value (.getString result 1))) "Plan"))]
          (if (contains? tables :unknown) :unknown tables)))
      (finally
        (run-quietly! statement "RESET plan_cache_mode")
        (run-quietly! statement "DEALLOCATE groundwork_postgres_plan")))))

(defn- ask-planner-safely
  "In a transaction, a failed EXPLAIN would end it; a savepoint keeps it."
  [^Connection connection query]
  (if (.getAutoCommit connection)
    (try (ask-planner connection query) (catch Exception _ :unknown))
    (let [^Savepoint savepoint (.setSavepoint connection)]
      (try (let [tables (ask-planner connection query)]
             (.releaseSavepoint connection savepoint)
             tables)
           (catch Exception _
             (.rollback connection savepoint)
             :unknown)))))

(defn- find-tables-named-in-comment
  "The table keys of a query's \"-- reads: orders billing/invoices\" line."
  [query]
  (when-let [[_ names] (re-find #"(?m)^\s*--\s*reads:\s*(.+)$" (:text-with-dollar-numbers query))]
    (set (map keyword (str/split (str/trim names) #"[\s,]+")))))

(defn- find-tables-of-query [log connection query]
  (let [known (:groundwork-postgres/tables-read query)]
    (or @known
        (let [planned (ask-planner-safely connection query)
              tables  (if (= :unknown planned) planned (into planned (find-tables-named-in-comment query)))]
          (when (= :unknown tables)
            ;; once per query: its answer is kept
            (log :warn :groundwork-postgres/tables-read-unknown {:groundwork-postgres/query (:groundwork-postgres/path query)}))
          (reset! known tables)))))

(defn note-tables-of-query!
  "Notes the tables a query reads, when a run-and-list-tables-read is running."
  [connection query]
  (when-let [{:keys [tables log]} *noted*]
    (let [read (find-tables-of-query log connection query)]
      (swap! tables #(if (or (= :unknown %) (= :unknown read)) :unknown (into % read))))))

(defn run-and-list-tables-read
  "Runs (f) and returns [what f returned, the tables every query it ran
   reads]: a set of table keys, or :unknown. A query the planner can't say
   is written to log, once."
  [log f]
  (binding [*noted* {:tables (atom #{}) :log log}]
    (let [result (f)]
      [result @(:tables *noted*)])))
