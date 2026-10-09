CREATE TABLE transfers (
    id                          BIGSERIAL PRIMARY KEY,
    reference                   VARCHAR(60)   NOT NULL,
    type                        VARCHAR(10)   NOT NULL,
    status                      VARCHAR(12)   NOT NULL,
    source_account_number       VARCHAR(10)   NOT NULL,
    destination_account_number  VARCHAR(10)   NOT NULL,
    destination_bank_code       VARCHAR(12),
    destination_bank_name       VARCHAR(120),
    destination_account_name    VARCHAR(160),
    recipient_code              VARCHAR(60),
    amount_kobo                 BIGINT        NOT NULL,
    fee_kobo                    BIGINT        NOT NULL DEFAULT 0,
    vat_kobo                    BIGINT        NOT NULL DEFAULT 0,
    stamp_duty_kobo             BIGINT        NOT NULL DEFAULT 0,
    total_debit_kobo            BIGINT        NOT NULL,
    narration                   VARCHAR(140),
    idempotency_key             VARCHAR(100)  NOT NULL,
    request_hash                VARCHAR(64)   NOT NULL,
    gateway_reference           VARCHAR(80),
    note                        VARCHAR(300),
    version                     BIGINT        NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfers_reference UNIQUE (reference),
    CONSTRAINT uq_transfers_idempotency UNIQUE (source_account_number, idempotency_key),
    CONSTRAINT ck_transfers_amount CHECK (amount_kobo > 0),
    CONSTRAINT ck_transfers_type CHECK (type IN ('INTERNAL', 'EXTERNAL')),
    CONSTRAINT ck_transfers_status CHECK (status IN ('PENDING', 'SUCCESSFUL', 'FAILED'))
);
CREATE INDEX ix_transfers_source ON transfers (source_account_number, created_at DESC);
CREATE INDEX ix_transfers_destination ON transfers (destination_account_number, created_at DESC);
CREATE INDEX ix_transfers_pending ON transfers (status, created_at);
