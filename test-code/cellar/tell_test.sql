-- A test's change function: tells nothing.
CREATE OR REPLACE FUNCTION public.tell_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$
