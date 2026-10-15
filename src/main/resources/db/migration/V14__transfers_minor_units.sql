-- Plan 002-004, step A1: transfers moves from kobo to minor units and gains a currency.
-- No new behaviour. Every existing row is a Naira row, so it keeps its numbers and becomes currency NGN.

ALTER TABLE transfers RENAME COLUMN amount_kobo      TO amount_minor;
ALTER TABLE transfers RENAME COLUMN fee_kobo         TO fee_minor;
ALTER TABLE transfers RENAME COLUMN vat_kobo         TO vat_minor;
ALTER TABLE transfers RENAME COLUMN stamp_duty_kobo  TO stamp_duty_minor;
ALTER TABLE transfers RENAME COLUMN total_debit_kobo TO total_debit_minor;

-- The default fills rows that already exist (and costs nothing on an empty table). It is removed straight
-- afterwards, so new code has to say which currency it means.
ALTER TABLE transfers ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'NGN';
ALTER TABLE transfers ALTER COLUMN currency DROP DEFAULT;

-- Used by cross-currency transfers (step A5). Empty for every transfer until then.
ALTER TABLE transfers ADD COLUMN target_currency VARCHAR(3);
ALTER TABLE transfers ADD COLUMN target_amount_minor BIGINT;
ALTER TABLE transfers ADD COLUMN quote_id UUID;