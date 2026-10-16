
-- Payout quotes use the payout provider's locked rate, not the general FX snapshot.
-- Existing conversion and transfer quotes retain their snapshot references.

ALTER TABLE quotes
    ALTER COLUMN snapshot_id DROP NOT NULL;
