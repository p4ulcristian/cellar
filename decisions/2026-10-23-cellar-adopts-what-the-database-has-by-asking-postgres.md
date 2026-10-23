# cellar adopts what the database already has, by asking Postgres how it prints the map's piece

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md section 1: 236 keys, 220 plain indexes, 65 partial ones, 191 checks, 53 triggers built under other names or texts). Built on tidy-up, not released.

**What was wrong:** cellar compared an existing key, index or trigger with the map's by its name (or by a hash of the map's text). Iron Rainbow's are named its own way (`user_designs_package_id_fkey`), and Postgres prints SQL back in its own form: the map says `WHERE appointment_proposal__status = 'pending'`, Postgres shows `(appointment_proposal__status = 'pending'::text)`. So cellar would drop and make again every one of them on production: every key re-checked, every index rebuilt.

**Decision:**

- **The same shape is the same thing.** A key with the same columns, target and actions, an index with the same columns, method, order and condition, a check with the same condition: cellar adopts it and renames it to its own name (`ALTER … RENAME`, metadata only: no scan, no rebuild).
- **Postgres says whether SQL is the same.** For a condition, an expression, a default or a view, cellar builds the map's piece on an empty copy of the table, inside a transaction it rolls back, and compares how Postgres prints it with the real one:

  ```sql
  BEGIN;
  CREATE TEMP TABLE cellar_probe (LIKE appointment_proposals);
  CREATE INDEX ON cellar_probe (appointment_proposal__workspace_id) WHERE appointment_proposal__status = 'pending';
  -- pg_get_expr of its predicate, compared with the real index's
  ROLLBACK;
  ```

  Same: adopted. Different: made again, as before.

**Why:** the only way that keeps Iron Rainbow's existing objects without a rebuild and stays exact, with nothing stored beside the database.

**The price:** making a plan opens a transaction with temporary objects: the migrating role needs the TEMP right, and every extension an expression uses must exist first.

**Instead of:**
- **a table recording what cellar made and how Postgres printed it**: IR's objects have no record, so the first deploy rebuilds them all
- **maps written in Postgres's printed form** (`'pending'::text`): every author learns how Postgres prints, and a new Postgres version can print differently
- **keeping names as the match**: every differently named key and index rebuilt on production

**Settled while building:**

1. **The probe's temporary tables are made from the maps** (each column's name, type and default), not `LIKE` the real table, so a column a pending change renames already has its new name. What Postgres has is renamed on paper the same way: a renamed column's whole name is replaced in its printed checks and indexes, never inside a quoted text or name.
2. **A piece the probe can't make is a stop**, with Postgres's words (`orders's check orders_x_check can't be made: …`): the map's SQL is wrong, or needs what isn't there.
3. **Only the tables Postgres has are probed** (and those a pending change renames a table into); a fresh database is not, nothing there to compare.
4. **A key is adopted on its columns and target**; its actions are then compared as any key's, so a changed action is still dropped and made again.
5. **Defaults are printed by the probe too**, in place of cellar foretelling Postgres's printing for its few default words.
6. **Triggers and policies aren't adopted**: they stay named by a hash of their SQL; one under another name is dropped and made again, which changes only the catalog (no scan, no rebuild).
7. **A check under a name cellar doesn't give, unlike the map's, is left alone**, as before; [checks declared like tables](2026-10-23-checks-functions-views-and-extensions-are-declared-like-tables.md) drops it.
8. **Given a transaction** (`does-database-match-tables?` as the app's role), the probe runs under a savepoint rolled back inside it; the transaction can't be read-only, since Postgres refuses `CREATE TEMP TABLE` there.
