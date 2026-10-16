-- Plan 002-004, step A4: the USD equivalent of every non-NGN transfer, from the rate snapshot used at the time.
-- It feeds the shared daily total (R-LIM2-3). NGN-to-NGN transfers and rows from before this step stay at 0.
ALTER TABLE transfers ADD COLUMN usd_equivalent_minor BIGINT NOT NULL DEFAULT 0;
