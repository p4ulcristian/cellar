-- The extensions in the database.
SELECT extname::text AS extension_name FROM pg_extension
