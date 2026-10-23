-- How many jobs a shop has: SQL, so Postgres would check its body against the jobs table.
CREATE OR REPLACE FUNCTION public.count_jobs(shop uuid) RETURNS bigint LANGUAGE sql STABLE AS $$
  SELECT count(*) FROM jobs WHERE job__shop_id = shop
$$
