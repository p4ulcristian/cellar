# Checks, functions, views and extensions are declared, compared and dropped like tables

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md section 1: 191 checks, 21 functions, 15 views, 3 extensions; defaults `uuidv7()` 95, jsonb/array literals 44, `CURRENT_DATE` 1). Builds on [cellar adopts what the database already has](2026-10-23-cellar-adopts-what-the-database-has-by-asking-postgres.md). Not built yet.

**What was wrong:** cellar read only checks named `_enum`/`_not_null`; functions, views and extensions lived only in change files, and on a fresh database (tests, previews) a change file is marked done, never run, so a fresh database had none of them. A default outside cellar's few words (`uuidv7()`) would be turned into `gen_random_uuid()`.

**Decision:**

- **A table's `:checks`**, each a condition: `:checks ["jsonb_typeof(design_rim__photos) = 'array'"]`, `[]` for none. Added NOT VALID, then validated in the slow part; compared by asking Postgres.
- **`migrate!` takes, beside the tables, every key written out:**
  ```clojure
  {:extensions ["pg_trgm" "cube" "earthdistance"]
   :functions  ["app/db/next_job_number.sql"]      ; one CREATE FUNCTION per file
   :views      ["app/db/report_totals.sql"]}       ; one CREATE VIEW per file
  ```
  Made in the order extensions, functions, tables, views; one no longer named is dropped; a view over a column that changes is dropped and made again around the change. Compared by asking Postgres.
- **New default words:** `:time-ordered-uuid` (uuidv7), `:today`, and `[]`/`{}` on json and array columns.
- **Text enums under other names** (about 63 checks) are matched by shape into `[:enum …]` and renamed.

**Why:** a fresh database is the same as production, and each object is said in one place.

**The price:** the biggest cellar change of Iron Rainbow's port (an estimate).

**Instead of:**
- **change files also run on a fresh database**: a fresh database is history replayed; the current function lives in whichever change last touched it; drift goes unseen
- **the app installs them at every start**: a view whose columns change can't be replaced in place, a removed function stays, nothing is compared
