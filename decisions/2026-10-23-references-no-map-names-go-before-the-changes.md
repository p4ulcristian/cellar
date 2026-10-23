# The references no map names go before the changes

> 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md section 1, C6: dropping v0.5.1's `groundwork` tables, which `workspace_invitations` still pointed at). Changes in part [Changes are data, and v1 makes tables, columns, references, enums and indexes](2026-10-22-changes-are-data-and-what-v1-makes.md). Built on tidy-up, not released.

**What was wrong:** `{:drop-table …}` stops while a reference points at the table. The plan checked that against Postgres's references before it dropped the ones no map names, and wrote `DROP TABLE` before `DROP CONSTRAINT`: a table that only a reference the maps had already removed pointed at couldn't be dropped in one deploy.

**Decision:** the references on the maps' tables that no map names (nor adopts), once the pending changes have run on paper, are dropped first: on paper before the changes are checked, and in the instant part before the changes' statements.

```
ALTER TABLE invitations DROP CONSTRAINT invitations_invitation__role_id_fkey   -- in no map
DROP TABLE legacy.roles                                                        -- change d1
```

**Why:** the map already says the reference is gone; the order is the plan's to get right, in one deploy.

**Instead of:**
- **two deploys**, the reference dropped by the first, the table by the second: the maps say both at once
- **a `{:drop-reference …}` change**: a second way to say what the map says

**Settled while building:**

1. **The folders of the tables pending changes drop or rename away are read too**, beside the maps' folders: a table in a folder no map names any more (`legacy.roles`) can be dropped; once the change has run, that folder isn't read again.
2. **A reference the plan adopts by its shape isn't dropped**, here as anywhere.
3. **A reference the maps name, with other actions, is still dropped after the changes** and made again: it points at a table the maps have, so it never blocks a drop.
