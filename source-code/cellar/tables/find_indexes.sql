-- The indexes of the tables in the folders the maps name, with their columns in order.
SELECT CASE WHEN n.nspname = 'public' THEN t.relname::text ELSE n.nspname || '.' || t.relname END AS table_name,
       i.relname::text  AS index_name,
       ix.indisvalid    AS valid,
       ix.indisunique   AS is_unique,
       ix.indisprimary  AS is_primary,
       (SELECT array_agg(a.attname::text ORDER BY k.ord)
        FROM unnest(ix.indkey::int2[]) WITH ORDINALITY k(attnum, ord)
        JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum) AS column_names
FROM pg_index ix
JOIN pg_class i     ON i.oid = ix.indexrelid
JOIN pg_class t     ON t.oid = ix.indrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE n.nspname = ANY (string_to_array(:folders, ',')) AND NOT (n.nspname = 'cellar' AND t.relname = 'changes_done')
