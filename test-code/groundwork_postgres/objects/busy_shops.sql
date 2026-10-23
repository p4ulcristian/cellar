-- The shops with more than one job: a view over a view.
CREATE VIEW busy_shops AS SELECT shop__id FROM jobs_per_shop WHERE shop__jobs > 1
