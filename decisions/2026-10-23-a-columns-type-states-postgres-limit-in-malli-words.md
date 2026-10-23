# A column's type states Postgres's limit, in malli's words

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md section 1: 401 columns in types cellar refused: varchar(n) 167, jsonb 98, integer 88, numeric(p,s) 24, arrays 13, char(n) 9, smallint 2). Not built yet; wire learns `:json` and decimal's `:precision`/`:scale` with it.

**Decision:** a column's type is a malli shape that states the limit Postgres holds, and cellar makes (and adopts) the Postgres type it names:

```clojure
[:background-job/status      [:string {:max 10}]]                        ; varchar(10)
[:asset/height               [:int {:min -2147483648 :max 2147483647}]]  ; integer
[:vehicle/year               [:int {:min -32768 :max 32767}]]            ; smallint
[:inventory/min-qty          [:decimal {:precision 10 :scale 2}]]        ; numeric(10,2)
[:look/flags                 [:vector [:string {:max 64}]]]              ; varchar(64)[]
[:appointment-proposal/slots [:json [:vector :map]]]                     ; jsonb
```

- An `[:enum …]` stored as varchar says its length: `[:enum {:max 10} "queued" "running" "done"]`.
- **`char(n)` has no word** (it pads with spaces): Iron Rainbow's 9 such columns move to text once, by a change file, after their row counts are checked on a copy.

**Why:** the limit is said once. The row shape made from the table (`turn-table-into-row-shape`) checks a browser's params, so a status too long for its column is refused as `:bridge/invalid` before the database, not answered as a 500 from it. Nothing on production is rewritten for the other 392 columns.

**The price:** wire learns `:json` and decimal's precision and scale; a type's words are a closed list cellar knows (a type outside it is still a stop).

**Instead of:**
- **Postgres's type written beside wire's word** (`{:postgres-type "integer"}`): every type covered, but the limit is known only to Postgres, so a value too long passes the shape and fails as a 500
- **moving Iron Rainbow to cellar's 8 types**: integer to bigint and char to text rewrite tables and their indexes under a full lock on production, a column a view reads refuses the change, and the limits are lost
