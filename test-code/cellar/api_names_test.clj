(ns cellar.api-names-test
  "The public names of cellar's api. Adding or removing one
   fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [cellar.api]))

(deftest the-api-names
  (is (= '#{settings-shape run-and-list-tables-read turn-table-name-into-table-key start-listening! stop-listening! is-listening?
           start-pool! stop-pool!
           make-query-from-sql-file make-query-from-sql-text
           find-rows find-one-row run-statement! run-in-transaction! is-transaction?
           make-plan-from-tables run-instant-part! run-slow-part! migrate!
           does-database-match-tables? turn-table-into-row-shape
           turn-key-into-column-name turn-table-key-into-table-name turn-column-type-into-postgres-type
           grant-tables-to-role!}
         (set (keys (ns-publics 'cellar.api))))))
