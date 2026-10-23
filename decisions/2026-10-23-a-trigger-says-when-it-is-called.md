# A trigger says when it is called

> 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md section 1, C5: `packages_move_their_variants … FOR EACH ROW WHEN (OLD.package__workspace_id IS DISTINCT FROM NEW.package__workspace_id)`). Changes in part [A table map says references over several columns, and the triggers a library declares](2026-10-23-a-table-map-says-references-over-several-columns-and-a-librarys-triggers.md). Built on tidy-up, not released.

**Decision:** a declared trigger writes out `:when`, nil to be called for every row (or statement), or a SQL condition Postgres checks first, on `OLD` and `NEW` for a row trigger:

```clojure
{:function "app.move_variants" :arguments [] :timing :after :events #{:update} :update-of nil :for-each :row
 :when "OLD.package__workspace_id IS DISTINCT FROM NEW.package__workspace_id"}
```

It is part of the trigger's SQL, so of the hash it is named by: another condition is another trigger, the old one dropped and the new one made, as any changed trigger (the catalog only).

**Why:** Postgres skips the call when the condition fails, before the function runs; the map says when it is called, in one place.

**Instead of:**
- **the test moved into the function's first line**: the function runs on every update of that table, and its trigger reads as if it always mattered
- **`:when` optional, unsaid for none**: every other key of a trigger is written out

**Settled while building:**

1. **The condition is written as given, inside `WHEN (…)`**, and hashed with the rest of the trigger's SQL; a condition Postgres refuses (`OLD` in an insert trigger, a column on a statement trigger) fails when the instant part runs, which then keeps nothing.
