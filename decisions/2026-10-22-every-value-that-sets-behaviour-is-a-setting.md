# Every value that sets behaviour is a setting; no hidden defaults

> **Changed in part** by [The pool and every query are counted on the app's meter](2026-10-23-the-pool-and-every-query-are-counted-on-the-apps-meter.md) (2026-10-23, v0.4.0): the pool's settings gain `:metric-buckets`, required.

> **Changed in part** by [HikariCP's and PgJDBC's lines go to the log the pool was given](2026-10-23-the-drivers-lines-go-to-the-pools-log.md) (2026-10-23, v0.3.0): the pool is named `cellar-<database>-<n>`, unique in the JVM (its connections' `ApplicationName` stays `cellar-<database>`); `start-pool!` takes `{:log log}`.

> **Changed in part** by [The app passes its log](2026-10-22-the-app-passes-its-log.md) (2026-10-22, v0.2.0): the listener writes to the app's log, `:log` in its options, not to `System.Logger` `cellar`.

> **Changed in part** by [A column says only the options it has; a table map still writes out its keys](2026-10-22-a-column-says-only-the-options-it-has.md) (2026-10-22, v0.1.3): a column says only the options it has, an unsaid one meaning no or none; a table map still writes out `:indexes`, `:gates` and `:on-change`, and gates all four operations.

> Changes in part Groundwork's "Settings are required data" ("HikariCP's own tuning (keepalive, max lifetime, connection wait) keeps Hikari's defaults"), [A query knows the tables it reads; a table can call a function on change; NOTIFY can be heard](2026-10-22-a-query-knows-its-tables-and-postgres-can-be-heard.md) (the listener's 50 ms batch), [Changes are data, and v1 makes tables, columns, references, enums and indexes](2026-10-22-changes-are-data-and-what-v1-makes.md) (the plan's parts ran under the pool's limits) and [cellar is its own library](2026-10-22-cellar-is-its-own-library.md) (its deps: malli joins them). Since v0.1.2.

**Decision:** every value that sets how cellar behaves is written out by the app: a setting, a required option or a required key, checked by malli before anything runs, the maps of settings and options closed, a missing or unknown key refused, named, with every other problem at once (`:cellar/bad-settings`, `:cellar/bad-options`, `:cellar/bad-tables`). No function fills in a value: there is no shorter arity, no `:or`, no optional key, and no default of HikariCP's, PgJDBC's or Postgres's left in effect unwritten. What stays fixed is listed below, each with its reason.

- **The pool's settings**, `settings-shape`, every key required:

  ```clojure
  [:map {:closed true}
   [:host :string] [:port :int] [:database :string] [:user :string]
   [:password [:maybe :string]]
   [:tls [:enum :verified :off]]
   [:tls-root-cert [:maybe :string]]                                     ; nil with :off
   [:pool-size [:int {:min 1}]]
   [:connection-wait-ms [:int {:min 250}]]                               ; HikariCP connectionTimeout, was 30000
   [:connection-check-wait-ms [:int {:min 250}]]                         ; validationTimeout, was 5000
   [:connection-max-lifetime-ms [:or [:= :none] [:int {:min 30000}]]]    ; maxLifetime, was 1800000
   [:connection-keepalive-every-ms [:or [:= :none] [:int {:min 30000}]]] ; keepaliveTime, was 120000
   [:leak-warning-after-ms [:or [:= :none] [:int {:min 2000}]]]          ; leakDetectionThreshold, was off
   [:connect-timeout-seconds [:int {:min 1}]]                            ; PgJDBC connectTimeout, was 10
   [:read-timeout-seconds [:or [:= :none] [:int {:min 1}]]]              ; PgJDBC socketTimeout, was none
   [:tcp-keepalive? :boolean]                                            ; PgJDBC tcpKeepAlive, was false
   [:statement-timeout-ms [:or [:= :none] [:int {:min 1}]]]
   [:lock-timeout-ms [:or [:= :none] [:int {:min 1}]]]                   ; was the role's, or none
   [:idle-in-transaction-timeout-ms [:or [:= :none] [:int {:min 1}]]]    ; was the role's, or none
   [:time-zone :string]]
  ```

  The minimums are HikariCP's: below them, and with a keepalive not shorter than the lifetime or a leak warning longer than it, HikariCP replaces the value with its default or turns it off, and says so only in a log line. cellar refuses them instead, so the value in effect is the one written. `:tls-root-cert` was an optional key; it is nil for the Java runtime's own certificates. `:lock-timeout-ms` and `:idle-in-transaction-timeout-ms` are set on every connection as it opens, beside `:statement-timeout-ms`, for the same reason: `:none` is 0, so a limit on the database role can't apply behind the settings' back.

- **A plan's two parts have their own time limits**, `{:statement-timeout-ms … :lock-timeout-ms …}`, required by `run-instant-part!` and `run-slow-part!`; `migrate!` takes `{:instant-part … :slow-part …}`. Before, both parts ran under the pool's limits: an index built `CONCURRENTLY` over a big table was cancelled at the pool's 30 s, and an `ALTER TABLE` waiting for its lock behind a long transaction held every query to that table behind it, for as long as the pool allowed. The instant part sets them for its transaction (`SET LOCAL`); the slow part runs on one connection and gives it back with the pool's limits.
- **The listener's numbers are options**: `start-listening!` takes `{:on-notifications :on-reconnect :batch-window-ms :check-stopped-every-ms :reconnect-after-ms :stop-wait-ms}`, every key required. They were fixed: 50 ms to gather a batch, 500 ms per wait, 1 s before reconnecting, 2 s for `stop-listening!`, which now returns whether the listener had ended.
- **`run-in-transaction!` takes its options always**, `{:read-only? false}`; the 2-argument form filling in `{}` is gone.
- **A table map writes out every key cellar reads**: `:table`, `:columns`, `:indexes` (`[]` for none), `:gates` and `:on-change` (nil for none). The map stays open: another library's keys (doorbell's `:doorbell`) ride along. **A column writes out its five options**: `[k type {:primary-key? false :optional? false :unique? false :default nil :references nil}]`; a column without an options map, or with one left out, is refused. **Gates write out all four operations**, `"false"` for none allowed: a missing one meant no policy, which Postgres treats as no.

**What stays fixed, and why:**

- **A fixed pool**: HikariCP's `minimumIdle` is `:pool-size` and `idleTimeout` 0, so no idle connection is closed (Groundwork's "Connections come from a HikariCP pool": no growing and shrinking under load).
- **`start-pool!` fails when it can't connect** (`initializationFailTimeout` 1): a wrong host or password shows where it is set, not at the first query; `does-database-match-tables?` runs right after anyway.
- **Statements commit by themselves outside `run-in-transaction!`** (`autoCommit` true), **read committed** whatever the role's default (`transactionIsolation`), **not read-only** (`readOnly` false; a transaction says `:read-only?`): the queries and the plan's statements are written for it.
- **The search path is only `public`**: no other schema catches an app's table.
- **TLS `:verified` is `verify-full`**: encrypted, the certificate checked and naming `:host`; a server without TLS is refused, never used plain. A security constant, not a setting.
- **The driver's tuning, which changes no answer**: `prepareThreshold` 5, `defaultRowFetchSize` 0 (cellar reads a whole result into a vector), no JMX beans, no pool suspension, internal queries not isolated.
- **Labels**: the pool and its connections are named `cellar-<database>` (in logs and `pg_stat_activity`).
- **`find-one-row` asks for at most two rows**: the second is how it knows there is more than one.
- **A name past 63 characters is cut to 54 and a hash**: Postgres's own limit.
- **A query's tables come from its generic plan** (`plan_cache_mode = force_generic_plan`, `cellar_plan`), and the `-- reads:` comment: the way it is asked, not a choice an app makes.
- **The listener reads NOTIFY outside a transaction** (autocommit), on a channel that is a plain Postgres name, and logs to `System.Logger` `cellar`.
- **The slow part runs one statement after another, without pacing**: each is one `CONCURRENTLY` build or `VALIDATE`, which Postgres paces itself; a pause between them would only make the deploy longer.

**Why:** a value left to a default is a value the app doesn't show. A 30 s wait for a connection, a connection replaced every 30 minutes, a lock wait the database role sets, an index build cancelled by the web's statement limit: each is behaviour a user feels, and each was invisible in the app's settings, set by cellar or by a library under it. Groundwork's "Settings are required data" left HikariCP's tuning at its defaults because Groundwork "only requires what it exposes"; that left exactly the values a production pool is tuned by unwritten. A column's options are the same: `{:optional? true}` left out reads the same whether NOT NULL was chosen or forgotten.

**The price:** a pool's settings grow from 10 keys to 20; every column writes five options, nearly all `false` or nil; every table writes `:indexes`, `:gates` and `:on-change`. An app or library moving from v0.1.1 changes its settings, its table maps, its calls of `run-in-transaction!`, `start-listening!`, `migrate!`, `run-instant-part!` and `run-slow-part!`.

**Instead of:**
- defaults the app may override: a good value without thought, but the value in effect is invisible, as before
- writing out only HikariCP's settings and leaving PgJDBC's and the role's: the app still couldn't read how long a dead network hangs a query, or what lock wait it runs under
- passing values HikariCP would change and letting it log: the setting would say one thing and the pool do another
- the migration limits in the pool's settings: they are the deploy's, and a deploy and a web server use one pool's settings differently
- a column's options optional, with false and nil as the defaults: shorter maps, but a forgotten `:optional? true` and a chosen NOT NULL read the same
