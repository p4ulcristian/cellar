# HikariCP's and PgJDBC's lines go to the log the pool was given

> Changes in part [The app passes its log](2026-10-22-the-app-passes-its-log.md) (HikariCP's lines still went through SLF4J, to whatever binding the app had; "an SLF4J binding inside cellar" was chosen against) and [Every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (the pool was named `cellar-<database>`). Since v0.3.0.

**Decision:** `start-pool!` takes options, every key required: `{:log log}`, the app's `(log level event data)`, checked by malli (`:cellar/bad-options` naming `:log`). HikariCP's own lines (the pool's start and stop, a leak warning after `:leak-warning-after-ms`, a connection that failed its check, a pool that can't add a connection) and PgJDBC's (a certificate whose name doesn't match, a connection never closed) about that pool are written to it:

| level | event | data |
|---|---|---|
| HikariCP's and PgJDBC's own: `:error` (severe), `:warn` (warning), `:info` | `:cellar/pool-said` | `:cellar/pool` the pool's name, `:cellar/logger` the logger's name (`com.zaxxer.hikari.pool.ProxyLeakTask`), `:cellar/message` the line as they wrote it, and `:error` their exception when there is one (the leak warning's is the stack where the connection was taken) |
| `:warn` | `:cellar/pool-lines-elsewhere` | `:cellar/pool`, `:cellar/slf4j-logger-factory`: SLF4J hands HikariCP's lines to another binding than slf4j-jdk14 (the app has one of its own), so they don't reach the log; said once per pool, as it starts |

**How a line finds its log.** SLF4J and java.util.logging each have one set of loggers for the whole JVM; HikariCP's loggers are one per class, shared by every pool, and take no logger of their own. So there is one bridge, and a registry keyed by the pool's name:

- cellar depends on slf4j-jdk14, SLF4J's own binding to java.util.logging, so the app adds no SLF4J backend: HikariCP's lines become java.util.logging lines, where PgJDBC's already are.
- cellar puts one handler on two java.util.logging loggers, `com.zaxxer.hikari` and `org.postgresql`, at info and up, and stops those two handing their lines on to the root's handlers (standard error). It touches no other logger: another library's SLF4J or java.util.logging lines go where java.util.logging sends them by default (the root's handler, standard error), as before they went nowhere (SLF4J without a binding).
- each pool's name is unique in the JVM, `cellar-<database>-<n>`, kept in a registry with its log from `start-pool!` until `stop-pool!` has closed it.
- a line's pool is, in this order: the name it starts with (`cellar-shop-2 - Start completed.`, HikariCP's lines about a pool); the thread's (`cellar-shop-2:housekeeper`, HikariCP's own threads, where a leak warning is said); or the pool cellar is working with on this thread: `start-pool!`, `stop-pool!`, and every connection cellar takes from a pool (a query, a transaction, the listener, the slow part) mark the thread for as long as they hold it (a leaked connection given back, PgJDBC opening a connection at the start).
- a line no pool owns (PgJDBC's cleaner thread finding a connection never closed) goes to each distinct log of the pools open now, `:cellar/pool` nil; with no pool open, to java.util.logging's root, as before.

So two apps in one JVM (the tests: a pool for the owner and one for the app's role, or two test pools at once) each hear their own pools' lines, and a line from a pool's housekeeper never reaches the other's log.

**What stays fixed, and why:**

- **Info and up**: HikariCP's debug lines are its pool's counts every 30 s and each connection's comings and goings; PgJDBC's are every message to and from Postgres, made only when the logger asks for them, at a cost on every query. The app's `:lowest-level` decides below that, as for any line.
- **The pool's name is `cellar-<database>-<n>`**, n counting the pools started in this JVM; the connections' `ApplicationName`, in `pg_stat_activity`, stays `cellar-<database>`.

**Why:** a leak warning is the line that says a request holds a connection it never gives back, and a pool that can't add a connection is the first sign Postgres is gone; both went to SLF4J without a binding (nowhere) or to a binding the app had to add and configure apart from its log. Through the pool's log they are JSON lines with the app's format and level, and an `:error` is kept for pulling like any other. The registry keyed by name, rather than one log for the whole JVM, keeps two pools' lines apart; HikariCP says the name in its lines and its threads, so no line needs guessing but the few on threads cellar doesn't hold.

**The price:**

- `start-pool!` takes an options map; every caller of v0.2 changes.
- cellar chooses the JVM's SLF4J binding by bringing one. An app with its own (logback, log4j) has two on the class path: SLF4J warns and takes one, and when it isn't slf4j-jdk14, HikariCP's lines go to the app's binding, not the log (`:cellar/pool-lines-elsewhere` says so). An app with jul-to-slf4j (java.util.logging into SLF4J) can't take cellar: other libraries' lines would go round between the two. A library's test alias that kept slf4j-nop has the same two bindings.
- An app that resets java.util.logging (`LogManager.readConfiguration`) after a pool starts takes the handler off; the lines then go to standard error.
- A line PgJDBC says on a thread of the app's own, with a connection the app took from the pool itself, has no pool: it goes to every open pool's log.

**Instead of:**
- an SLF4J binding of cellar's own (a Java class behind a service file): the right shape, but cellar is Clojure source, taken as a git dependency; a compiled class needs a prep step in every app
- one log for the JVM, the last pool's: two pools with different logs (the tests, two apps in one JVM) would hear each other's lines
- the pool's name through SLF4J's MDC: HikariCP sets none, and its housekeeper's lines are on its own threads, where cellar can set nothing
- `:log` in the pool's settings: the settings are values in the app's config; options take functions (as `start-listening!`'s do)
- leaving the lines to SLF4J: a leak warning would still reach no one unless the app adds and configures a backend beside its log
