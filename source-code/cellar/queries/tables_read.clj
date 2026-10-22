(ns cellar.queries.tables-read
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
   [cellar.queries.names :as names]
   [jsonista.core :as json])
  (:import
   (java.lang System$Logger System$Logger$Level)
   (java.sql Connection Savepoint Statement)))

(def ^:private ^System$Logger logger (System/getLogger "cellar"))

(def ^:dynamic ^:private *noted*
  "An atom of the tables read so far, while run-and-list-tables-read runs; nil otherwise."
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
      (.execute statement (str "PREPARE cellar_plan AS " (:text-with-dollar-numbers query)))
      (.execute statement "SET plan_cache_mode = force_generic_plan")
      (with-open [result (.executeQuery statement
                                        (str "EXPLAIN (VERBOSE, FORMAT JSON) EXECUTE cellar_plan"
                                             (when-let [names (seq (:param-names query))]
                                               (str "(" (str/join ", " (repeat (count names) "NULL")) ")"))))]
        (.next result)
        (let [tables (collect-tables (get (first (json/read-value (.getString result 1))) "Plan"))]
          (if (contains? tables :unknown) :unknown tables)))
      (finally
        (run-quietly! statement "RESET plan_cache_mode")
        (run-quietly! statement "DEALLOCATE cellar_plan")))))

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

(defn- find-tables-of-query [connection query]
  (let [known (:cellar/tables-read query)]
    (or @known
        (let [planned (ask-planner-safely connection query)
              tables  (if (= :unknown planned) planned (into planned (find-tables-named-in-comment query)))]
          (when (= :unknown tables)
            (.log logger System$Logger$Level/WARNING
                  (str "The planner can't say which tables " (:cellar/path query)
                       " reads; any change counts for its answers")))
          (reset! known tables)))))

(defn note-tables-of-query!
  "Notes the tables a query reads, when a run-and-list-tables-read is running."
  [connection query]
  (when *noted*
    (let [tables (find-tables-of-query connection query)]
      (swap! *noted* #(if (or (= :unknown %) (= :unknown tables)) :unknown (into % tables))))))

(defn run-and-list-tables-read
  "Runs (f) and returns [what f returned, the tables every query it ran
   reads]: a set of table keys, or :unknown."
  [f]
  (binding [*noted* (atom #{})]
    (let [result (f)]
      [result @*noted*])))
