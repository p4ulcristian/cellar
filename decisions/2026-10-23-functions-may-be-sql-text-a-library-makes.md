# :functions may be SQL text a library makes, as well as files

> The user's decision, 2026-10-23. Changes in part [Checks, functions, views and extensions are declared, compared and dropped like tables](2026-10-23-checks-functions-views-and-extensions-are-declared-like-tables.md). Not built yet.

**What was wrong:** doorman makes some functions from each app's rules (`doorman.via_…`, `denied_…`, `unchanged_…`, `"may … on …"`, `fill_…`): the suspended-shop deny becomes `doorman.denied_ab12()`, which every gate calls. Their text exists only once the app's rules are known, so no file can name them; `:functions` took only files, and migrate dropped every function in a named folder it wasn't told about.

**Decision:** an entry of `:functions` is a classpath file or a made function, every key written out:

```clojure
:functions ["doorman/find_asking_person.sql"
            {:name "doorman.denied_ab12" :sql "CREATE FUNCTION doorman.denied_ab12() …"}]
```

A made function is compared (by asking Postgres), made, kept and dropped exactly like a file's: made before the tables' gates that call it, dropped when the rule that made it is gone. doorman hands its made functions to the app, which passes them to `migrate!` with the rest.

**Why:** every function in a folder cellar reads is declared, so nothing is dropped by surprise and a fresh database has them all.

**The price:** two forms of entry in `:functions`.

**Instead of:**
- **writing the made functions out as files ahead of time and committing them**: every rule change means regenerating and committing
- **a folder cellar never reads**: undeclared objects beside the declared ones, which the C4 decision rejected
