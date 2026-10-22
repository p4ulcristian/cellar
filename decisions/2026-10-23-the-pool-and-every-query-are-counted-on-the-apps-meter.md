# The pool and every query are counted on the app's meter; a meter holds one pool

> Changes in part [Every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (the pool's settings gain `:metric-buckets`) and [The app passes its log](2026-10-22-the-app-passes-its-log.md) (`start-pool!`'s and `start-listening!`'s options gain `:meter`). Since v0.4.0, on meter v0.1.2.

**Decision:** `start-pool!` and `start-listening!` take the app's meter (meter's `make-meter`) as a required option, `:meter`, never nil, beside `:log`, and declare cellar's metrics on it before anything connects:

| metric | type | labels | from |
|---|---|---|---|
| `cellar_pool_connections` | gauge, read when the text is written | `state`: `active`, `idle` | HikariCP's pool bean, live |
| `cellar_pool_waiting_threads` | gauge, read | | the same |
| `cellar_connection_acquire_seconds` | histogram | | HikariCP's metrics tracker: each wait for a connection |
| `cellar_connection_usage_seconds` | histogram | | the tracker: how long each was held |
| `cellar_connection_timeouts_total` | counter | | the tracker: a wait past `:connection-wait-ms` |
| `cellar_query_seconds` | histogram | `query`: the queries' names | every query cellar runs, from sending it to its last row read, failed or not |
| `cellar_listening_reconnects_total` | counter | | `start-listening!`: listening again after a break, as `:on-reconnect` is called |

- **Every query is timed by its name**: its `.sql` file's path, or the name `make-query-from-sql-text` was given. Each query made is noted when it is made (a `def` loading); `start-pool!` declares the names noted by then as the `query` label's set. A query made after the pool started (a statement a migration makes from a table map, a name made per call) is counted as `"other"`, and meter says the first one at `:warn`. A query's own planning for `run-and-list-tables-read` isn't in its time.
- **The pool and every transaction carry the meter**, so `find-rows` and the others need no new argument; a map cellar didn't make, without a meter, records nothing.
- **A meter holds one pool's metrics.** Two pools in one JVM (the deploy's owner pool beside the app's) each take a meter of their own; `start-pool!` on a meter that already holds a pool is refused by meter before anything connects (`:meter/bad-metrics`, declared differently: its read gauges read another pool, and its query set may differ). A pool started again after a stop takes a new meter too.
- **The buckets are settings**, `:metric-buckets {:query-seconds […] :connection-acquire-seconds […] :connection-usage-seconds […]}`, required, in seconds; meter checks they go up.
- **Listening takes the meter the app hands it**, as it takes the log: doorbell's listener is counted here, not again by doorbell.
- `migrate!`, `run-and-list-tables-read` and the table functions take no meter: they count nothing of their own; their queries are timed by the pool they run on.

**Why:** a slow query, a pool too small (threads waiting, long waits, timeouts) and a listener that keeps breaking are what an app on Postgres must see first, without reading the log. The queries' names are a closed set cellar already knows, since every query is made by `def` from its file ("A query is made by a function from its .sql file"); timing every one means no query is left out by someone forgetting to wrap it. HikariCP's own tracker sees the waits and timeouts where they happen; its bean already holds the counts, so they are read, not kept twice.

A meter per pool, rather than a `pool` label: meter keeps a declaration as it was first made, so a second pool can't add its name to a set; and the pools in one process are the app's and, in a deploy, the owner's, which no dashboard wants added together. A metric name per pool would make the names data.

**The price:** every app writes `:meter` and three bucket settings, and a second pool a second meter (served on its own route, or not at all, as the deploy's isn't). A query made after the start, by name, is lost into `"other"`. The `query` label makes one histogram's series per query, times the buckets plus three: an app with 300 queries and 12 buckets needs `:most-series-per-metric` above 4,500.

**Instead of:**
- timing only the queries the app wraps: the ones nobody wrapped are the ones that turn slow unseen
- the query's text as its label: one series per statement made from data, and long
- a `pool` label, the pools declared up front: `start-pool!` would need every pool's name before the first starts
- HikariCP's Prometheus tracker (`PrometheusHistogramMetricsTracker`): it needs Prometheus' client, which meter replaces, and its own buckets
- a dynamic label set the pool adds to when a query is made: meter's sets are closed at the declaration, so no caller grows them
