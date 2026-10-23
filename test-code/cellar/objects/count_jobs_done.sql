-- How many jobs a shop has done: the same function, another body.
CREATE OR REPLACE FUNCTION public.count_jobs(shop uuid) RETURNS bigint LANGUAGE sql STABLE AS $$
  SELECT count(*) FROM jobs WHERE job__shop_id = shop AND job__status = 'done'
$$
