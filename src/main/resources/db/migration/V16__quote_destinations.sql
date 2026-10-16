-- Plan 002-004, step A5 (P2): where a transfer or payout quote is going. The destination and the name are
-- fixed at quote time and cannot change at confirm. A5 uses kind PAYBRIDGE_ACCOUNT; B3 reuses the table
-- with BANK_ACCOUNT and the nullable columns below.
CREATE TABLE quote_destinations (
                                    quote_id                 UUID PRIMARY KEY REFERENCES quotes (id),
                                    kind                     VARCHAR(20)  NOT NULL,
                                    account_number           VARCHAR(40)  NOT NULL,
                                    account_name             VARCHAR(140) NOT NULL,
                                    currency                 VARCHAR(3)   NOT NULL,
                                    bank_code                VARCHAR(20),
                                    bank_name                VARCHAR(100),
                                    country                  VARCHAR(2),
                                    bank_fields              TEXT,
                                    recipient_type           VARCHAR(20),
                                    purpose                  VARCHAR(30),
                                    verification_source      VARCHAR(20),
                                    rate_mode                VARCHAR(20),
                                    provider_rate_ref        VARCHAR(100),
                                    provider_rate_expires_at TIMESTAMPTZ,
                                    CONSTRAINT ck_quote_destinations_kind CHECK (kind IN ('PAYBRIDGE_ACCOUNT', 'BANK_ACCOUNT'))
);