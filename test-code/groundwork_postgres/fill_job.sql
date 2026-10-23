-- A test's fill trigger: a job takes its order's shop.
CREATE OR REPLACE FUNCTION public.fill_job() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  SELECT order__shop_id INTO NEW.job__shop_id FROM orders WHERE order__id = NEW.job__order_id;
  RETURN NEW;
END $$
