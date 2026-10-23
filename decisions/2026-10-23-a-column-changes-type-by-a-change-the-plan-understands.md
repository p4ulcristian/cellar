# A column changes type by a change the plan understands

> The user's decision, 2026-10-23, from Iron Rainbow's port (its 9 `char(n)` columns move to text once, per [A column's type states Postgres's limit, in malli's words](2026-10-23-a-columns-type-states-postgres-limit-in-malli-words.md)). Built on tidy-up, not released.

**What was wrong:** the map says `:string`, the database says `character(3)`: cellar stops on the mismatch before any change file runs, so the change file that would move the column can never run.

**Decision:** a change kind cellar understands:

```clojure
{:change-column-type :partner-price-sheet/currency :from "character(3)"}
```

The plan knows this change ends that column's mismatch: it doesn't stop on it, runs `ALTER TABLE … ALTER COLUMN … TYPE …` (the map's type) in the slow part, and compares the column after. `:from` must be what the database has, or the plan stops, named; once done, the change is marked done like any other.

**Why:** the move is part of the plan, reproducible on every database (prod, a preview's copy, a test), and checked before and after.

**The price:** one more change kind; a type change that rewrites the table takes its lock in the slow part, so its size is checked on a copy first.

**Instead of:**
- **two deploys**: the first would need a map that says `char(3)`, which a map can't
- **changing the columns by hand on production**: not reproducible, and the plan still stops on a copy that wasn't changed

**Settled while building:**

1. **The table is the one map with the column**: no map, or several, is a stop, named. Renames earlier in the list are followed (the change runs on paper after them).
2. **The statement is `ALTER TABLE t ALTER COLUMN c TYPE <the map's type> USING c::<the map's type>`**, first in the slow part; the `USING` cast lets a type without an assignment cast move too.
3. **Marked done in the slow part, right after the `ALTER`**, not with the instant part's marks: a failed `ALTER` leaves the change pending, and the next plan runs it again. A pending change whose column already has the map's type is only marked done, in the instant part.
4. **`does-database-match-tables?` lets the app start while only the type change is left**, as it does for any slow part.
5. **A declared view reading the column is a stop**: Postgres refuses the `ALTER` while the view stands, and the slow part runs while the app does, so the view can't go away and come back there. Taking the view out of `:views` for one deploy drops it first.
6. **A plain column mismatch's stop names the change to write**, `{:change-column-type <its key> :from "<what Postgres has>"}`.
