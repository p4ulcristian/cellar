-- The currencies the price sheets use.
CREATE VIEW currencies AS SELECT DISTINCT price_sheet__currency FROM price_sheets
