# The app passes its log; cellar never decides how a line is written

> Changes in part [Every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (the listener logged to `System.Logger` `cellar`) and [A query knows the tables it reads](2026-10-22-a-query-knows-its-tables-and-postgres-can-be-heard.md) (`run-and-list-tables-read` took only the function). Since v0.2.0.

**Decision:** cellar writes no line itself. Every function that has something to say takes the app's log, `(log level event data)` (logbook's, "A log call is level, event, data"), as a required key, `:log`, in its options, checked by malli as a function (`settings/log-entry`, `:cellar/bad-options` naming `:log`):

| function | where `:log` goes | what is written |
|---|---|---|
| `migrate!` | its options, beside `:instant-part` and `:slow-part` | `:info :cellar/planned {:cellar/part :instant-or-:slow :cellar/why … :cellar/sql …}`, each statement of the plan, which it printed before |
| `start-listening!` | its options | `:warn :cellar/listening-broke {:cellar/channel … :cellar/reconnect-after-ms … :error e}`; `:error :cellar/listener-function-failed {:cellar/channel … :error e}` when `:on-notifications` or `:on-reconnect` throws |
| `run-and-list-tables-read` | a new options map, first: `(run-and-list-tables-read {:log log} f)` | `:warn :cellar/tables-read-unknown {:cellar/query path}`, once per query, when Postgres's planner can't say which tables it reads |

`run-and-list-tables-read` is called while a request runs (doorbell's notes), so the log it is given is the request's, and the line carries its request id.

cellar does not depend on logbook: it only calls the function it is handed. HikariCP's own lines (a leak warning after `:leak-warning-after-ms`, a pool that can't connect) still go through SLF4J, to whatever binding the app puts on its classpath: SLF4J has one binding per JVM, not one per pool, and HikariCP takes no logger of its own.

**Why:** one function is one value the app hands to every library, and the app's logbook alone decides the format, the lowest level and what is kept. `System.Logger` and `println` wrote where the JVM's setup said, in a shape no shipper could read, and without the request a line came from.

**The price:** `migrate!`, `start-listening!` and `run-and-list-tables-read` take one key more, and `run-and-list-tables-read` an options map; every caller of v0.1 changes. A leak warning is still outside the app's log.

**Instead of:**
- `:log` in the pool's settings, the pool carrying it: the settings are values written in the app's config, and a line from a request's query would carry the pool's log, not the request's
- `:log` on every function, even those that never write a line: a key with nothing to do
- an SLF4J binding inside cellar routing HikariCP's lines to the log: it would take over every library's SLF4J lines in the JVM
