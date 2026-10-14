-- Step 6 of multi-currency wallets: conversion quotes and quotes ledger linkage
CREATE TABLE quotes (
    id                   UUID PRIMARY KEY,
    customer_id          BIGINT NOT NULL REFERENCES customers (id),
    type                 VARCHAR(20) NOT NULL,
    source_currency      VARCHAR(3) NOT NULL,
    target_currency      VARCHAR(3) NOT NULL,
    source_amount_minor  BIGINT NOT NULL,
    fee_minor            BIGINT NOT NULL DEFAULT 0,
    fee_reason           VARCHAR(20) NOT NULL,
    net_minor            BIGINT NOT NULL,
    target_amount_minor  BIGINT NOT NULL,
    rate                 NUMERIC(24,12) NOT NULL,
    snapshot_id          BIGINT NOT NULL REFERENCES fx_rate_snapshots (id),
    usd_equivalent_minor BIGINT NOT NULL,
    status               VARCHAR(20) NOT NULL,
    expires_at           TIMESTAMPTZ NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at         TIMESTAMPTZ,
    failure_reason       VARCHAR(200),
    journal_key          VARCHAR(60),
    CONSTRAINT ck_quotes_type CHECK (type IN ('CONVERSION', 'TRANSFER')),
    CONSTRAINT ck_quotes_status CHECK (status IN ('ACTIVE', 'EXECUTING', 'COMPLETED', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_quotes_fee_reason CHECK (fee_reason IN ('PROMO', 'STANDARD')),
    CONSTRAINT ck_quotes_source_amount CHECK (source_amount_minor > 0),
    CONSTRAINT ck_quotes_net_amount CHECK (net_minor > 0),
    CONSTRAINT ck_quotes_target_amount CHECK (target_amount_minor > 0),
    CONSTRAINT ck_quotes_usd_amount CHECK (usd_equivalent_minor >= 0)
);

CREATE INDEX ix_quotes_customer_status ON quotes (customer_id, status);
CREATE INDEX ix_quotes_status_expires ON quotes (status, expires_at);
CREATE INDEX ix_quotes_created_at ON quotes (customer_id, created_at);
