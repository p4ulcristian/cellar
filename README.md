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

Needs only next.jdbc, PgJDBC, HikariCP and jsonista. One public namespace,
`cellar.api`; its parts are `cellar.queries.*` and `cellar.tables.*`.

## The api

| function | does |
|---|---|
| `start-pool!` `stop-pool!` `settings-shape` | a HikariCP pool; every setting required (TLS, pool size, statement time limit, time zone); the settings as a malli shape |
| `make-query-from-sql-file` `make-query-from-sql-text` | a query from a `.sql` file on the classpath (read when its `def` loads), or from SQL text code makes |
| `find-rows` `find-one-row` `run-statement!` | run a query on a pool or a transaction |
| `run-in-transaction!` | `(f transaction)` in one transaction, kept or undone; `{:read-only? true}` refuses writes |
| `make-plan-from-tables` | what would bring Postgres to the maps and changes, running nothing: `{:instant :slow :stops :changes-to-mark}` |
| `run-instant-part!` `run-slow-part!` | run a plan's two parts (the deploy) |
| `migrate!` | plan, run the instant part now and the slow part on a thread (development) |
| `does-database-match-tables?` | whether the app can start: no instant part, stop or change left |
| `grant-tables-to-role!` | let the app's own role, which owns nothing, use the tables through their gates |
| `turn-table-into-row-shape` | a table's rows as a malli shape |
| `turn-key-into-column-name` `turn-table-key-into-table-name` `turn-table-name-into-table-key` | names: `:order/shop-id` ↔ `order__shop_id`, `:billing/invoices` ↔ `billing.invoices` |
| `run-and-list-tables-read` | runs a function and returns what it returned with the tables its queries read, as Postgres's planner says |
| `start-listening!` `stop-listening!` | `LISTEN` on a channel, notifications in batches, a reconnect said |

```clojure
(require '[cellar.api :as cellar])

(def pool (cellar/start-pool! {:host "localhost" :port 5432 :database "shop" :user "shop" :password nil
                               :tls :off :pool-size 10 :statement-timeout-ms 30000 :time-zone "UTC"}))

(def shops
  {:table   :shops
   :columns [[:shop/id   :uuid   {:primary-key? true :default :random-uuid}]
             [:shop/name :string]]})

(def orders
  {:table   :orders
   :columns [[:order/id            :uuid         {:primary-key? true :default :random-uuid}]
             [:order/shop-id       :uuid         {:references :shop/id}]
             [:order/status        [:enum "draft" "paid" "coated"]]
             [:order/total         :decimal      {:default 0}]
             [:order/customer-note :string       {:optional? true}]
             [:order/created-at    :time/instant {:default :now}]]
   :indexes [[:order/shop-id :order/created-at]]})

(def partner-payouts                     ; a namespaced key: the table billing.partner_payouts
  {:table   :billing/partner-payouts
   :columns [[:partner-payout/id     :uuid    {:primary-key? true :default :random-uuid}]
             [:partner-payout/amount :decimal]]})

(cellar/migrate! pool [shops orders partner-payouts]
                 [{:id "2026-10-23-rename-order-note" :rename-column [:orders :order/note :order/customer-note]}])

;; app/orders/find_orders_of_shop.sql:
;;   SELECT order__id, order__total FROM orders WHERE order__shop_id = :order/shop-id
(def find-orders-of-shop (cellar/make-query-from-sql-file "app/orders/find_orders_of_shop.sql"))
(cellar/find-rows pool find-orders-of-shop {:order/shop-id shop-id})
;=> [#:order{:id #uuid "1d000000-…" :total 120.50M}]
```

A table map may also have `:gates`, SQL conditions per operation
(`{:read "…" :insert "…" :update "…" :delete "…"}`) that become row-level
security, and `:on-change`, the name of a Postgres function and its
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
