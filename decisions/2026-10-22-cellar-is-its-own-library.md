# cellar is its own library

> **Changed in part** by [Every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (2026-10-22, v0.1.2): its `deps.edn` names malli too, which checks the settings, the options and the table maps.

> Changes in part [A query knows the tables it reads; a table can call a function on change; NOTIFY can be heard](2026-10-22-a-query-knows-its-tables-and-postgres-can-be-heard.md): its names.

**Decision:** cellar is a library of its own, in its own public repository, under the MIT license; an app or a framework takes it from git by tag. It was Groundwork's library `groundwork.server.database`; now it is the namespace `cellar`, with one public namespace, `cellar.api`, and its parts in `cellar.queries.*` and `cellar.tables.*`. Its `.sql` files are on the classpath under `cellar/`. Its own `deps.edn` names only next.jdbc, PgJDBC, HikariCP and jsonista, and its tests run with nothing else on the classpath. It knows nothing of who uses it:

- its bookkeeping table is `cellar.changes_done`, in its own folder (`groundwork.changes_done` before); `grant-tables-to-role!` lets the app's own role read it, so that role can still check the database (in Groundwork the table shared a folder with Groundwork's own tables, which were granted);
- the changed rows an `:on-change` function reads are `cellar_new_rows` / `cellar_old_rows` (`groundwork_new_rows` / `groundwork_old_rows` before), and the plan it prepares to learn a query's tables is `cellar_plan`;
- `:gates` are SQL conditions and `:on-change` names a Postgres function; the app, or a library on top, makes them and passes them in.

**Why:** talking to Postgres and keeping its tables matched to maps is one idea any Clojure app with Postgres has. Its own repository, with a `deps.edn` naming only what it requires, proves it needs nothing more, and lets an app take it alone. No deployed database had `groundwork.changes_done` yet, so the renames cost nothing.

**The price:** a change to cellar is a new tag, and the app moves to it on purpose. The decisions it was built on stayed with Groundwork.

**Instead of:**
- staying a folder in Groundwork's tree: no app can take it alone, and nothing proves its deps
- a name under `groundwork`: cellar isn't Groundwork's
- the names quarry, postgres-maps and tables: cellar was chosen over them
