-- Plan 002-004, step C3: exceptions found by matching a batch against the partner statement (R-SET-8 to 10).

-- Matching state lives on the batch (status and these two stamps may move; identity and totals may not, see V20).
--   matched_at      first pass done (uses the statement stored when the batch closed)
--   final_check_at  pass after the statement lag done (re-fetches the statement, decides everything left)
ALTER TABLE settlement_batches
    ADD COLUMN matched_at      TIMESTAMPTZ,
    ADD COLUMN final_check_at  TIMESTAMPTZ;

CREATE TABLE settlement_exceptions (
                                       id                    BIGSERIAL PRIMARY KEY,
                                       batch_id              BIGINT       NOT NULL REFERENCES settlement_batches (id),
                                       reference             VARCHAR(100) NOT NULL,
                                       type                  VARCHAR(25)  NOT NULL,
                                       our_amount_minor      BIGINT,
                                       partner_amount_minor  BIGINT,
                                       our_status            VARCHAR(20),
                                       partner_status        VARCHAR(40),
                                       created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                       resolved_at           TIMESTAMPTZ,
                                       resolved_by           VARCHAR(60),
                                       resolution_note       VARCHAR(500),
                                       CONSTRAINT uq_settlement_exception UNIQUE (batch_id, reference),
                                       CONSTRAINT ck_settlement_exception_type CHECK (type IN
                                                                                      ('MISSING_AT_PARTNER', 'MISSING_ON_OUR_SIDE', 'AMOUNT_DIFFERS', 'STATUS_DIFFERS', 'STILL_PENDING')),
                                       CONSTRAINT ck_settlement_exception_resolution CHECK (
                                           (resolved_at IS NULL AND resolved_by IS NULL AND resolution_note IS NULL)
                                               OR (resolved_at IS NOT NULL AND resolved_by IS NOT NULL AND length(btrim(resolution_note)) > 0))
);

CREATE INDEX ix_settlement_exception_open ON settlement_exceptions (id) WHERE resolved_at IS NULL;

-- R-SET-10: nothing is edited or deleted. The only change allowed is resolving an open exception, once.
CREATE FUNCTION settlement_exception_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'settlement exceptions are never deleted';
END IF;
    IF OLD.resolved_at IS NOT NULL
       OR NEW.batch_id <> OLD.batch_id OR NEW.reference <> OLD.reference OR NEW.type <> OLD.type
       OR NEW.our_amount_minor IS DISTINCT FROM OLD.our_amount_minor
       OR NEW.partner_amount_minor IS DISTINCT FROM OLD.partner_amount_minor
       OR NEW.our_status IS DISTINCT FROM OLD.our_status
       OR NEW.partner_status IS DISTINCT FROM OLD.partner_status
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'settlement exception % can only be resolved, once', OLD.id;
END IF;
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_settlement_exception_guard BEFORE UPDATE OR DELETE ON settlement_exceptions
    FOR EACH ROW EXECUTE FUNCTION settlement_exception_guard();