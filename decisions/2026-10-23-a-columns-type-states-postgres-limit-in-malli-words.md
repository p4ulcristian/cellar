# A column's type states Postgres's limit, in malli's words

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md section 1: 401 columns in types cellar refused: varchar(n) 167, jsonb 98, integer 88, numeric(p,s) 24, arrays 13, char(n) 9, smallint 2). Built on tidy-up, not released; wire learns `:json` and decimal's `:precision`/`:scale` with it ([wire's decision](https://github.com/p4ulcristian/wire/blob/tidy-up/decisions/2026-10-23-a-databases-json-and-a-decimals-digits-are-shapes.md)).

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

**Settled while building:**

1. **Each word takes exactly the properties listed, or none**: `:int`'s `{:min :max}` are integer's or smallint's exactly (`[:int {:min 0}]` is refused), `:string` and `:enum` take only `{:max n}`, `:decimal` both `:precision` and `:scale`; anything else is refused at the start, `:cellar/unknown-type`.
2. **An `:enum`'s values fit its `{:max n}`**, or it is refused at the start.
3. **An array's items are one plain type**: not an `:enum`, a `:vector` (no arrays of arrays) or `:json`.
4. **wire is cellar's test dependency only**, pinned by its tidy-up sha: the tests read the row shape with wire; cellar itself still needs no wire to run.
5. **A type that differs from Postgres's is still a stop, even with a change file that alters it in the same plan**: a `:run-sql-file`'s SQL isn't read on paper. The 9 `char(n)` columns move to text before the deploy whose maps say `:string` (or a change kind for it is decided).
6. **A param is sent as it is**: a vector or a map isn't turned into an array or jsonb (unchanged); a query writes them as text with a cast (`CAST(:job/slots AS jsonb)`).
7. **The api gains `turn-column-type-into-postgres-type`**, so a library writing SQL over a map's columns (doorman's fill functions declare variables of a column's type) uses cellar's list, not a copy of the old eight words.
