-- ONE-TIME RESET (plan 001 step 1, spec R-MIG-1). Wipes all data, restarts ids.
-- TRUNCATE is used on purpose: the append-only triggers only fire for row-level UPDATE/DELETE.
-- There are no foreign keys, so table order does not matter.
TRUNCATE TABLE
    blocked_destinations,
    fraud_flags,
    notifications,
    audit_log,
    transfers,
    ledger_entries,
    accounts,
    customers
    RESTART IDENTITY;

-- Re-create the three system accounts that V2 created. (V9 will move their balances into NGN wallets.)
INSERT INTO accounts (account_number, customer_id, balance_kobo, account_type) VALUES ('9000000001', 0, 0,
                                                                                       'SYSTEM'), -- fee income
                                                                                      ('9000000002', 0, 0,
                                                                                       'SYSTEM'), -- VAT payable
                                                                                      ('9000000003', 0, 0, 'SYSTEM');
-- stamp duty payable

-- The table is empty now, so a NOT NULL column can be added without a default.
ALTER TABLE ledger_entries RENAME COLUMN amount_kobo TO amount_minor;
ALTER TABLE ledger_entries ADD COLUMN currency VARCHAR(3) NOT NULL;