# An index is a map with every key written out; unique plain columns are :uniques

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md section 1: 21 GIN trigram, 65 partial, 19 DESC/NULLS indexes; 55 unique indexes; 18 multi-column unique constraints). Builds on [cellar adopts what the database already has](2026-10-23-cellar-adopts-what-the-database-has-by-asking-postgres.md). Built on tidy-up, not released.

**What was wrong:** `:indexes` were lists of columns, meaning, unsaid, btree and ascending; unique indexes were skipped and multi-column unique constraints couldn't be said. An index or unique key no map named was dropped, so Iron Rainbow's first migrate would have dropped 101 indexes and 18 unique keys.

**Decision:**

- **An index is a map, every key written out:**
  ```clojure
  :indexes [{:columns [[:customer/workspace-id :ascending] "lower(customer__email)"]   ; a column with its order, or an expression
             :method  :btree                                                       ; :btree :gin :gist :hash :brin
             :ops     {}                                                           ; per column: {:customer/email :gin-trgm-ops}
             :unique? true
             :where   "customer__email IS NOT NULL AND customer__email <> ''"}     ; nil for every row
            {:columns [[:customer/email :ascending]] :method :gin :ops {:customer/email :gin-trgm-ops} :unique? false :where nil}]
  ```
  A column's order is `:ascending`/`:descending` with its nulls written: `[:col :descending :nulls-last]`.
- **Unique keys over plain columns** are a table's `:uniques` (one column on the column, several on the table): `:uniques [[:conversation/kind :conversation/subject-id]]`. They become constraints; a unique with a condition or an expression is an index map with `:unique? true`.
- An existing plain unique index becomes its constraint by `ADD CONSTRAINT … UNIQUE USING INDEX`, without a rebuild; conditions and expressions are compared by asking Postgres.

**Why:** every Iron Rainbow index and uniqueness rule can be said, nothing is rebuilt, and a fresh database (tests, previews) has all of them.

**The price:** a longer form for a plain index; every table map rewrites its `:indexes`.

**Instead of:**
- **a bare column list as the short form**: hides "btree, ascending, every row"
- **every unique as an index map, no `:uniques`**: one form, but the 18 constraints are of another kind and each is rebuilt once
- **unique indexes left outside the maps**: a fresh database lacks 55 uniqueness rules

**Settled while building:**

1. **A column's order always says its nulls**, `[:customer/workspace-id :ascending :nulls-last]`: the two-word `[… :ascending]` above would hide them.
2. **An expression says its order too**, `["lower(customer__email)" :ascending :nulls-last]`, not a bare string.
3. **A method that keeps no order** (`:gin`, `:gist`, `:hash`, `:brin`) writes its columns `[:customer/email :unordered]`; a `:btree` index's columns are ordered; `:unique?` only on `:btree`. Anything else is refused at the start, `:cellar/bad-index`, as is an `:ops` key that is none of the index's columns.
4. **`:ops` names a class by its keyword**, `:gin-trgm-ops` for `gin_trgm_ops`, keyed by the column's key or the expression's text.
5. **A plain index keeps the name cellar always gave it** (`<table>_<columns>_idx`: btree, plain columns ascending with nulls last, no `:ops`, every row, not unique), so a database cellar made renames nothing. Any other is named after its columns and a hash of its SQL: a changed one is a new index, built `CONCURRENTLY` before the old one is dropped, so the table is never without one.
6. **An index under the map's name that Postgres prints otherwise** (changed by hand) is dropped and built again, `CONCURRENTLY`.
7. **A plain unique index becomes the key only when it is valid**, in the instant part; an invalid one is left to be dropped, and the key is built as a new one.
