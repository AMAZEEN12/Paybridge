CREATE TABLE accounts (
    id              BIGSERIAL PRIMARY KEY,
    account_number  VARCHAR(10)  NOT NULL,
    customer_id     BIGINT       NOT NULL,
    balance_kobo    BIGINT       NOT NULL DEFAULT 0,
    account_type    VARCHAR(10)  NOT NULL DEFAULT 'CUSTOMER',
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_accounts_number UNIQUE (account_number),
    CONSTRAINT ck_accounts_balance CHECK (balance_kobo >= 0),
    CONSTRAINT ck_accounts_type CHECK (account_type IN ('CUSTOMER', 'SYSTEM')),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'FROZEN'))
);
CREATE INDEX ix_accounts_customer ON accounts (customer_id);

-- System accounts that receive charges. customer_id 0 means "no customer".
INSERT INTO accounts (account_number, customer_id, balance_kobo, account_type) VALUES
    ('9000000001', 0, 0, 'SYSTEM'),   -- fee income
    ('9000000002', 0, 0, 'SYSTEM'),   -- VAT payable
    ('9000000003', 0, 0, 'SYSTEM');   -- stamp duty payable
