# cellar adopts what the database already has, by asking Postgres how it prints the map's piece

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md section 1: 236 keys, 220 plain indexes, 65 partial ones, 191 checks, 53 triggers built under other names or texts). Not built yet.

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
