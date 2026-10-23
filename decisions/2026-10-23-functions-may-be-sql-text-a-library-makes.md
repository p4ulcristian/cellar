# :functions may be SQL text a library makes, as well as files

> The user's decision, 2026-10-23. Changes in part [Checks, functions, views and extensions are declared, compared and dropped like tables](2026-10-23-checks-functions-views-and-extensions-are-declared-like-tables.md). Built on tidy-up, not released.

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

**Settled while building:**

1. **A made function's `:name` is its name as its SQL writes it**, folder and all, quoted where Postgres needs quotes (`doorman."may orders/edit on orders"`); its SQL must make that name, else `:cellar/bad-function`. Both keys are required (malli, `:cellar/bad-tables`).
2. **A function's name may be quoted**, in a file too: Postgres keeps it without its quotes; an unquoted one is compared in lower case, as Postgres folds it.
3. **Each entry of `:functions` is known by its place in the list** while planning (the probe's printed definitions, the drops), so two made functions of one name with other arguments (overloads) are a function each; a message names a file by its path and a made one as "the made function <name>"; a made function's plan line says "made by a library".
4. **What is read is unchanged**: the folders of the tables and of the declared functions and views. A made function whose folder nothing declared names any more is not seen, so not dropped; doorman's folder always holds its tables and files.
