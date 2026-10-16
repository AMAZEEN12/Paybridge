-- Plan 002-004, step C2: daily settlement batches, their lines, the stored partner statement, and a run marker.
CREATE TABLE settlement_batches (
                                    id                   BIGSERIAL PRIMARY KEY,
                                    partner              VARCHAR(20) NOT NULL,
                                    currency             VARCHAR(3)  NOT NULL,
                                    period_date          DATE        NOT NULL,               -- the UTC day
                                    period_start         TIMESTAMPTZ NOT NULL,
                                    period_end           TIMESTAMPTZ NOT NULL,
                                    status               VARCHAR(10) NOT NULL,
                                    item_count           INT         NOT NULL,
                                    our_total_minor      BIGINT      NOT NULL,
                                    partner_total_minor  BIGINT      NOT NULL,
                                    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
                                    closed_at            TIMESTAMPTZ,
                                    settled_at           TIMESTAMPTZ,
                                    CONSTRAINT uq_settlement_batch UNIQUE (partner, currency, period_date),
                                    CONSTRAINT ck_settlement_batch_status CHECK (status IN ('OPEN', 'CLOSED', 'SETTLED', 'EXCEPTION')),
                                    CONSTRAINT ck_settlement_batch_count CHECK (item_count >= 0)
);

CREATE TABLE settlement_batch_lines (
                                        id              BIGSERIAL PRIMARY KEY,
                                        batch_id        BIGINT      NOT NULL REFERENCES settlement_batches (id),
                                        reference       VARCHAR(100) NOT NULL,
                                        kind            VARCHAR(12) NOT NULL,
                                        amount_minor    BIGINT      NOT NULL,
                                        our_status      VARCHAR(20) NOT NULL,
                                        partner_status  VARCHAR(40),                              -- filled by matching (C3)
                                        match_result    VARCHAR(30),                              -- filled by matching (C3)
                                        CONSTRAINT uq_settlement_batch_line UNIQUE (batch_id, reference),
                                        CONSTRAINT ck_settlement_line_kind CHECK (kind IN ('PAYOUT', 'CONVERSION', 'TRANSFER'))
);

CREATE TABLE partner_statement_lines (
                                         id            BIGSERIAL PRIMARY KEY,
                                         partner       VARCHAR(20)  NOT NULL,
                                         currency      VARCHAR(3)   NOT NULL,
                                         reference     VARCHAR(100) NOT NULL,
                                         amount_minor  BIGINT       NOT NULL,
                                         status        VARCHAR(40)  NOT NULL,
                                         partner_time  TIMESTAMPTZ,
                                         fetched_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                         batch_id      BIGINT       REFERENCES settlement_batches (id),
                                         CONSTRAINT uq_statement_line UNIQUE (batch_id, reference)
);

-- A day the run has finished (even when it produced no batch), so catch-up does not look at it again.
CREATE TABLE settlement_runs (
                                 period_date  DATE PRIMARY KEY,
                                 completed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- R-SET-5: once a batch is CLOSED its identity and totals cannot change; only its status may move on (C3).
CREATE FUNCTION settlement_batch_immutable() RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'OPEN' AND (NEW.partner <> OLD.partner OR NEW.currency <> OLD.currency
        OR NEW.period_date <> OLD.period_date OR NEW.period_start <> OLD.period_start
        OR NEW.period_end <> OLD.period_end OR NEW.item_count <> OLD.item_count
        OR NEW.our_total_minor <> OLD.our_total_minor OR NEW.partner_total_minor <> OLD.partner_total_minor) THEN
        RAISE EXCEPTION 'settlement batch % is closed and cannot be changed', OLD.id;
END IF;
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_settlement_batch_immutable BEFORE UPDATE ON settlement_batches
    FOR EACH ROW EXECUTE FUNCTION settlement_batch_immutable();