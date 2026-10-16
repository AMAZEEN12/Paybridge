-- Plan 002-004, step B2: the payout record (module remittance) and one row per request sent to the provider.
CREATE TABLE remittances (
                             id                    BIGSERIAL PRIMARY KEY,
                             reference             VARCHAR(60)  NOT NULL UNIQUE,
                             customer_id           BIGINT       NOT NULL REFERENCES customers (id),
                             quote_id              UUID         NOT NULL UNIQUE REFERENCES quotes (id),
                             source_account_number VARCHAR(20)  NOT NULL,
                             source_currency       VARCHAR(3)   NOT NULL,
                             payout_currency       VARCHAR(3)   NOT NULL,
                             source_amount_minor   BIGINT       NOT NULL,
                             fee_minor             BIGINT       NOT NULL,
                             payout_amount_minor   BIGINT       NOT NULL,
                             purpose               VARCHAR(30)  NOT NULL,
                             status                VARCHAR(12)  NOT NULL,
                             conversion_done       BOOLEAN      NOT NULL DEFAULT FALSE,   -- used only when retries are on
                             attempts              INT          NOT NULL DEFAULT 0,       -- 0 to 3, retries only
                             next_retry_at         TIMESTAMPTZ,
                             rate_difference_minor BIGINT,                                -- OWN_LOCK only (journal J6)
                             provider              VARCHAR(20)  NOT NULL,
                             provider_payout_ref   VARCHAR(100),
                             provider_status       VARCHAR(40),
                             failure_reason        VARCHAR(300),
                             idempotency_key       VARCHAR(100) NOT NULL,
                             request_hash          VARCHAR(64)  NOT NULL,
                             version               BIGINT       NOT NULL DEFAULT 0,
                             created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
                             updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
                             completed_at          TIMESTAMPTZ,
                             CONSTRAINT uq_remittances_idempotency UNIQUE (source_account_number, idempotency_key),
                             CONSTRAINT ck_remittances_status CHECK (status IN ('PENDING', 'RETRYING', 'SUCCESSFUL', 'FAILED')),
                             CONSTRAINT ck_remittances_provider CHECK (provider IN ('FLUTTERWAVE')),
                             CONSTRAINT ck_remittances_source_amount CHECK (source_amount_minor > 0),
                             CONSTRAINT ck_remittances_fee CHECK (fee_minor >= 0),
                             CONSTRAINT ck_remittances_payout_amount CHECK (payout_amount_minor > 0),
                             CONSTRAINT ck_remittances_attempts CHECK (attempts BETWEEN 0 AND 3)
);

CREATE INDEX ix_remittances_customer ON remittances (customer_id, created_at DESC);
CREATE INDEX ix_remittances_status ON remittances (status, updated_at);

CREATE TABLE remittance_attempts (
                                     id                  BIGSERIAL PRIMARY KEY,
                                     remittance_id       BIGINT      NOT NULL REFERENCES remittances (id),
                                     attempt             INT         NOT NULL,                    -- 0 for the original request
                                     customer_reference  VARCHAR(60) NOT NULL UNIQUE,             -- the reference we sent; never reused
                                     provider_payout_ref VARCHAR(100),
                                     result              VARCHAR(30) NOT NULL,
                                     created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                                     CONSTRAINT uq_remittance_attempt UNIQUE (remittance_id, attempt),
                                     CONSTRAINT ck_remittance_attempt_number CHECK (attempt BETWEEN 0 AND 3)
);