(ns groundwork-postgres.tables.gates
  "Gates: Postgres's row rules (row-level security), from a table map's
   :gates, SQL conditions the app (or a library on top) passes in:

     {:table :orders … :gates {:read \"…\" :insert \"…\" :update \"…\" :delete \"false\"}}

   All four operations are written out (groundwork-postgres.settings/table-shape). Each
   with a condition other than \"false\" becomes one policy; \"false\" is
   no policy, which Postgres treats as no. A
   policy's name ends in a hash of its SQL, so a changed condition is a new
   name: the old policy is dropped and the new one made, no text compared."
  (:require
   [groundwork-postgres.tables.names :as names]))

(def ^:private commands
  {:read   {:command "SELECT" :using? true  :check? false}
   :insert {:command "INSERT" :using? false :check? true}
   :update {:command "UPDATE" :using? true  :check? true}
   :delete {:command "DELETE" :using? true  :check? false}})

(defn- make-policy-sql [table-name policy-name operation condition]
  (let [{:keys [command using? check?]} (get commands operation)]
    (str "CREATE POLICY " policy-name " ON " table-name " FOR " command
         (when using? (str " USING (" condition ")"))
         (when check? (str " WITH CHECK (" condition ")")))))

(defn turn-gates-into-policies
  "{policy-name sql} for a table's gates."
  [table-name gates]
  (into {}
        (for [[operation condition] gates
              :when (not= "false" condition)
              :let [policy-name (names/make-identifier (names/find-table-part table-name) (name operation)
                                                       (format "%08x" (bit-and (hash condition) 0xffffffff)))]]
          [policy-name (make-policy-sql table-name policy-name operation condition)])))
