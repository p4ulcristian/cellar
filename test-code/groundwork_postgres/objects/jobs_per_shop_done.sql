-- Done jobs per shop, read as the asker.
CREATE VIEW jobs_per_shop WITH (security_invoker = true) AS
  SELECT job__shop_id AS shop__id, count(*) AS shop__jobs FROM jobs WHERE job__status = 'done' GROUP BY job__shop_id
