
-- Step B3: allow quotes for bank payouts.
-- Preserve existing conversion and transfer quotes.

ALTER TABLE quotes
DROP CONSTRAINT ck_quotes_type;

ALTER TABLE quotes
    ADD CONSTRAINT ck_quotes_type
        CHECK (type IN ('CONVERSION', 'TRANSFER', 'PAYOUT'));
