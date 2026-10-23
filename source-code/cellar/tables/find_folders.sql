-- The folders (Postgres schemas) in the database.
SELECT nspname::text AS folder FROM pg_namespace
