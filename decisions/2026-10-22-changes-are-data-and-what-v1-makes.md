# Changes are data, and v1 makes tables, columns, references, enums and indexes

> **Changed in part** by [Every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (2026-10-22, v0.1.2): each part of a plan runs under its own time limits, which `run-instant-part!`, `run-slow-part!` and `migrate!` take, not the pool's; a table map writes out `:indexes`, `:gates` and `:on-change`, and a column its five options.

> Builds on Groundwork's decision "A table is one map".

**Decision:**

- **Changes are a vector of maps the app passes in**, each with an `:id`: `:rename-table`, `:rename-column`, `:drop-column` and `:drop-table` (both with a `:why`), and `:run-sql-file` for a backfill. Not files in a folder: a vector works the same from a jar, and is read like the tables.
- **A plan is made on paper first:** the pending changes are applied to what Postgres has, then compared with the maps. A stop means nothing runs. The instant part runs in one transaction with the changes it marks done.
- **v1 makes:** tables, columns (type, required or optional, default, primary key), references (`NOT VALID`, then `VALIDATE` in the slow part), enums (a check, the same way), indexes (`CONCURRENTLY`, in the slow part), and a column becoming required (a check `NOT VALID`, then `VALIDATE`, `SET NOT NULL` and the check dropped, in the slow part). After a rename it renames what it named after the old names.
- **v1 stops on:** a column or table in Postgres that no map has, a type change, a primary key change, a required column without a default on a table with rows, and a default computed per row (`:random-uuid`) added to a table with rows.
- **v1 reads only what it makes:** checks named `…_enum` or `…_not_null` and indexes that are neither unique nor a primary key; other constraints and indexes are left alone.

**Why:** what a map can say is made from the map; what it can't is a stop with the change file to add. Nothing is guessed, and nothing that blocks writes runs before the app starts.

**The price:** type changes, unique constraints, gates (`:access`) and live updates' triggers aren't in yet. A `:run-sql-file` backfill runs in the instant part, as written; batching it is up to its SQL.

**Instead of:**
- change files in a classpath folder: listing a folder inside a jar is unreliable
- running each change at once and comparing after: a stop would come after some changes had run
