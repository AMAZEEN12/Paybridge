CREATE TABLE fraud_flags (
    id               BIGSERIAL PRIMARY KEY,
    reference        VARCHAR(60),
    account_number   VARCHAR(10)   NOT NULL,
    decision         VARCHAR(10)   NOT NULL,
    reasons          VARCHAR(500)  NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_fraud_flags_account ON fraud_flags (account_number, created_at DESC);

CREATE TABLE blocked_destinations (
    id               BIGSERIAL PRIMARY KEY,
    destination_key  VARCHAR(30)   NOT NULL,
    reason           VARCHAR(200),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_blocked_destination UNIQUE (destination_key)
);
