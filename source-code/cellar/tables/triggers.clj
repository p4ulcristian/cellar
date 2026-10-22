(ns cellar.tables.triggers
  "Change triggers: a table map's :on-change names a function Postgres
   calls after every statement that inserts, updates or deletes its rows:

     {:table :orders … :on-change {:function \"app.tell_change\" :arguments [\"order__id\" \"order__shop_id\"]}}

   Three triggers, one per kind of statement, each FOR EACH STATEMENT, with
   the changed rows as tables the function reads: cellar_new_rows (after
   an insert or update) and cellar_old_rows (after an update or delete).
   A trigger's name ends in a hash of its SQL, so a changed :on-change is a
   new name: the old trigger is dropped and the new one made."
  (:require
   [clojure.string :as str]
   [cellar.tables.names :as names]))

(def ^:private statements
  {"insert" "AFTER INSERT ON %s REFERENCING NEW TABLE AS cellar_new_rows"
   "update" "AFTER UPDATE ON %s REFERENCING OLD TABLE AS cellar_old_rows NEW TABLE AS cellar_new_rows"
   "delete" "AFTER DELETE ON %s REFERENCING OLD TABLE AS cellar_old_rows"})

(defn- quote-text [s] (str "'" (.replace (str s) "'" "''") "'"))

(defn turn-on-change-into-triggers
  "{trigger-name sql} for a table's :on-change."
  [table-name {:keys [function arguments]}]
  (into {}
        (for [[operation when-text] statements
              :let [body (str (format when-text table-name) " FOR EACH STATEMENT EXECUTE FUNCTION "
                              function "(" (str/join ", " (map quote-text arguments)) ")")
                    trigger-name (names/make-identifier (names/find-table-part table-name) "on" operation (format "%08x" (bit-and (hash body) 0xffffffff)))]]
          [trigger-name (str "CREATE TRIGGER " trigger-name " " body)])))
