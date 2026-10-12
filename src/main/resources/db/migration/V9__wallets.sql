-- Step 1 of the multi-currency work, part 2 (plan 001): balances move from accounts into wallets.
-- Only NGN wallets exist after this migration. Every account (customer and system) gets one, flagged as home.
CREATE TABLE wallets (
                         id             BIGSERIAL PRIMARY KEY,
                         account_id     BIGINT       NOT NULL,
                         currency       VARCHAR(3)   NOT NULL,
                         balance_minor  BIGINT       NOT NULL DEFAULT 0,
                         status         VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
                         is_home        BOOLEAN      NOT NULL DEFAULT FALSE,
                         version        BIGINT       NOT NULL DEFAULT 0,
                         created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
                         CONSTRAINT fk_wallets_account FOREIGN KEY (account_id) REFERENCES accounts (id),
                         CONSTRAINT uq_wallets_account_currency UNIQUE (account_id, currency),
                         CONSTRAINT ck_wallets_balance CHECK (balance_minor >= 0),
                         CONSTRAINT ck_wallets_status CHECK (status IN ('ACTIVE', 'PARKED'))
);

-- Carry every existing balance over, so this is safe even if accounts already hold money.
INSERT INTO wallets (account_id, currency, balance_minor, status, is_home)
SELECT id, 'NGN', balance_kobo, 'ACTIVE', TRUE FROM accounts;

-- Dropping the column also drops ck_accounts_balance, which only referred to it.
ALTER TABLE accounts DROP COLUMN balance_kobo;