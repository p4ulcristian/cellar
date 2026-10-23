# An index is a map with every key written out; unique plain columns are :uniques

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md section 1: 21 GIN trigram, 65 partial, 19 DESC/NULLS indexes; 55 unique indexes; 18 multi-column unique constraints). Builds on [cellar adopts what the database already has](2026-10-23-cellar-adopts-what-the-database-has-by-asking-postgres.md). Not built yet.

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
