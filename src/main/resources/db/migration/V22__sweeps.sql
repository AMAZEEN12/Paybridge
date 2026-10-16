-- Plan 002-004, step C4: sweeps (R-SET-11). One row per sweep; the journal is SWEEP:<id>.
CREATE TABLE sweeps (
                        id                   BIGSERIAL PRIMARY KEY,
                        source_account       VARCHAR(20) NOT NULL,
                        currency             VARCHAR(3)  NOT NULL,
                        amount_minor         BIGINT      NOT NULL,
                        destination_account  VARCHAR(20) NOT NULL,
                        journal_key          VARCHAR(80) NOT NULL,
                        created_by           VARCHAR(60) NOT NULL,
                        created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
                        CONSTRAINT ck_sweep_amount CHECK (amount_minor > 0),
                        CONSTRAINT uq_sweep_journal UNIQUE (journal_key)
);

-- Nothing is edited or deleted: a mistake is fixed with a new journal.
CREATE FUNCTION sweeps_guard() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'sweeps are never edited or deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sweeps_guard BEFORE UPDATE OR DELETE ON sweeps
    FOR EACH ROW EXECUTE FUNCTION sweeps_guard();