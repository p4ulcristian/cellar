-- A test's trigger: counts its calls in the session setting test.moves.
CREATE OR REPLACE FUNCTION public.count_moves() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM set_config('test.moves', (coalesce(nullif(current_setting('test.moves', true), ''), '0')::int + 1)::text, false);
  RETURN NEW;
END $$
