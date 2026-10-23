-- The jobs' notes: reads a column a change drops.
CREATE VIEW notes_of_jobs AS SELECT job__id, job__note FROM jobs
