# A query knows the tables it reads; a table can call a function on change; NOTIFY can be heard

> **Changed in part** by [The app passes its log](2026-10-22-the-app-passes-its-log.md) (2026-10-22, v0.2.0): `run-and-list-tables-read` takes `{:log log}` first, and a listener's break and failures are written to the app's log.

> **Changed in part** by [Every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (2026-10-22, v0.1.2): the listener's batch window, its wait, its reconnect delay and its stop wait are `start-listening!`'s required options, not 50 ms, 500 ms, 1 s and 2 s.

> **Changed in part** by [cellar is its own library](2026-10-22-cellar-is-its-own-library.md) (2026-10-22): the changed rows are `cellar_new_rows` / `cellar_old_rows`, and the changes done are remembered in `cellar.changes_done`.

**Decision:** three additions to groundwork.server.database, for groundwork.server.sync:

- **`run-and-list-tables-read`** runs a function and returns what it returned with the tables its row queries read. Each query is planned once by Postgres, without running it: prepared under its own name with `$1…`, explained with the generic plan forced (`plan_cache_mode = force_generic_plan`, so no NULL that stands in for a param folds a scan away), then forgotten. Views count as their tables. A query Postgres can't prepare without values is `:unknown`. A function that reads tables inside hides them; the query names them in a comment line, `-- reads: orders shops`. Inside a transaction the plan runs under a savepoint, so a refused one doesn't end it. A query made by `make-query-from-sql-*` remembers its tables.
- **`:on-change`** on a table map (`{:function "groundwork.tell_sync" :arguments ["order__id" …]}`) makes three statement triggers, after insert, update and delete, with the changed rows as `groundwork_new_rows` / `groundwork_old_rows`. Named by a hash of their SQL, made and dropped like policies; triggers in the folders the maps name that no map asks for are dropped.
- **`start-listening!`** holds one connection of the pool on `LISTEN`, hands on what arrives within 50 ms as one batch, reconnects after a break and says so (what came in between was missed).

**Why:** what a query reads is known to Postgres exactly (views, joins, subqueries), so nobody declares it. Triggers and LISTEN are Postgres's own; nothing else to run.

**The price:** a query calling a function that reads tables must say so in its comment, or its screens miss those changes; one held connection per server for listening; a hand-made trigger on a mapped table is dropped (the maps say everything, as for policies). `EXPLAIN (GENERIC_PLAN)` was tried first: through JDBC its `$1` become the statement's own params, and bound NULLs fold the scans away.

**Also:** planning only reads, so the app's own role can check a database it can't change; `groundwork.changes_done` is made by migrating, not by planning.

**Instead of:** parsing SQL for table names (misses views and functions); a column listing tables on each query (forgotten); a separate listening connection outside the pool (its own settings, TLS twice).
