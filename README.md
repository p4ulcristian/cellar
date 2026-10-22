# cellar

Postgres from Clojure, in two halves. **Queries:** every query is a `.sql`
file; a param is `:name`, a column `entity__field` comes back as
`:entity/field`, with real types (UUID, BigDecimal, java.time).
**Tables:** declared as Clojure maps; cellar compares them with Postgres and
plans what differs, in two parts (instant, before the app starts; slow, after
it, in the background). A difference that could lose data stops with a
message and runs nothing; what a map can't say (a rename, a drop, a backfill)
is a change, given as data and run once.

```clojure
io.github.p4ulcristian/cellar {:git/tag "v0.1.1" :git/sha "1354d94"}
```

Needs only next.jdbc, PgJDBC, HikariCP, jsonista and malli. One public namespace,
`cellar.api`; its parts are `cellar.queries.*` and `cellar.tables.*`.

## The api

| function | does |
|---|---|
| `start-pool!` `stop-pool!` `settings-shape` | a HikariCP pool; every setting required (TLS, the pool's waits and lifetimes, the network's timeouts, the session's limits and time zone); the settings as a malli shape |
| `make-query-from-sql-file` `make-query-from-sql-text` | a query from a `.sql` file on the classpath (read when its `def` loads), or from SQL text code makes |
| `find-rows` `find-one-row` `run-statement!` | run a query on a pool or a transaction |
| `run-in-transaction!` | `(f transaction)` in one transaction, kept or undone; `{:read-only? true}` refuses writes, `false` written out otherwise |
| `make-plan-from-tables` | what would bring Postgres to the maps and changes, running nothing: `{:instant :slow :stops :changes-to-mark}` |
| `run-instant-part!` `run-slow-part!` | run a plan's two parts (the deploy), each under its own statement and lock time limits |
| `migrate!` | plan, run the instant part now and the slow part on a thread (development) |
| `does-database-match-tables?` | whether the app can start: no instant part, stop or change left |
| `grant-tables-to-role!` | let the app's own role, which owns nothing, use the tables through their gates |
| `turn-table-into-row-shape` | a table's rows as a malli shape |
| `turn-key-into-column-name` `turn-table-key-into-table-name` `turn-table-name-into-table-key` | names: `:order/shop-id` ↔ `order__shop_id`, `:billing/invoices` ↔ `billing.invoices` |
| `run-and-list-tables-read` | runs a function and returns what it returned with the tables its queries read, as Postgres's planner says |
| `start-listening!` `stop-listening!` | `LISTEN` on a channel, notifications in batches, a reconnect said; its batch window, waits and reconnect delay written out |

```clojure
(require '[cellar.api :as cellar])

(def pool (cellar/start-pool! {:host "localhost" :port 5432 :database "shop" :user "shop" :password nil
                               :tls :off :tls-root-cert nil
                               :pool-size 10
                               :connection-wait-ms 30000 :connection-check-wait-ms 5000
                               :connection-max-lifetime-ms 1800000 :connection-keepalive-every-ms 120000
                               :leak-warning-after-ms :none
                               :connect-timeout-seconds 10 :read-timeout-seconds :none :tcp-keepalive? false
                               :statement-timeout-ms 30000 :lock-timeout-ms :none
                               :idle-in-transaction-timeout-ms :none
                               :time-zone "UTC"}))

;; a column is [key type options], its five options always written out
(def no-options {:primary-key? false :optional? false :unique? false :default nil :references nil})

(def shops
  {:table     :shops
   :columns   [[:shop/id   :uuid   (assoc no-options :primary-key? true :default :random-uuid)]
               [:shop/name :string no-options]]
   :indexes   []
   :gates     nil
   :on-change nil})

(def orders
  {:table     :orders
   :columns   [[:order/id            :uuid         (assoc no-options :primary-key? true :default :random-uuid)]
               [:order/shop-id       :uuid         (assoc no-options :references :shop/id)]
               [:order/status        [:enum "draft" "paid" "coated"] no-options]
               [:order/total         :decimal      (assoc no-options :default 0)]
               [:order/customer-note :string       (assoc no-options :optional? true)]
               [:order/created-at    :time/instant (assoc no-options :default :now)]]
   :indexes   [[:order/shop-id :order/created-at]]
   :gates     nil
   :on-change nil})

(def partner-payouts                     ; a namespaced key: the table billing.partner_payouts
  {:table     :billing/partner-payouts
   :columns   [[:partner-payout/id     :uuid    (assoc no-options :primary-key? true :default :random-uuid)]
               [:partner-payout/amount :decimal no-options]]
   :indexes   []
   :gates     nil
   :on-change nil})

(cellar/migrate! pool [shops orders partner-payouts]
                 [{:id "2026-10-23-rename-order-note" :rename-column [:orders :order/note :order/customer-note]}]
                 {:instant-part {:statement-timeout-ms 30000 :lock-timeout-ms 5000}   ; an ALTER waits 5 s for its lock, then fails
                  :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}}) ; an index build takes what it takes

;; app/orders/find_orders_of_shop.sql:
;;   SELECT order__id, order__total FROM orders WHERE order__shop_id = :order/shop-id
(def find-orders-of-shop (cellar/make-query-from-sql-file "app/orders/find_orders_of_shop.sql"))
(cellar/find-rows pool find-orders-of-shop {:order/shop-id shop-id})
;=> [#:order{:id #uuid "1d000000-…" :total 120.50M}]
```

Every setting, option and table key is required: a missing or unknown one
is refused, named, with every other problem at once, before anything runs
(`:cellar/bad-settings`, `:cellar/bad-options`, `:cellar/bad-tables`). The
values above are HikariCP's and PgJDBC's own defaults, written out; what
stays fixed, and why, is in
[the decision](decisions/2026-10-22-every-value-that-sets-behaviour-is-a-setting.md).

A table map's `:gates`, nil or SQL conditions for all four operations
(`{:read "…" :insert "…" :update "…" :delete "false"}`), become row-level
security, and its `:on-change`, nil or the name of a Postgres function and its
arguments (`{:function "app.tell_change" :arguments ["order__id"]}`), called
by statement triggers after each insert, update and delete, with the changed
rows as `cellar_new_rows` and `cellar_old_rows`. cellar makes neither: the
app, or a library on top, passes them in. cellar keeps the changes it has run
in its own table, `cellar.changes_done`.

## Tests

```sh
clojure -X:test     # the Postgres tests are skipped without a database

# with a throwaway Postgres:
initdb -D /tmp/cellar-pg -A trust -U "$USER"
pg_ctl -D /tmp/cellar-pg -o "-p 5499 -k /tmp -c listen_addresses=localhost" -l /tmp/cellar-pg.log start
CELLAR_TEST_PORT=5499 CELLAR_TEST_USER=$USER clojure -X:test
pg_ctl -D /tmp/cellar-pg stop
```

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
