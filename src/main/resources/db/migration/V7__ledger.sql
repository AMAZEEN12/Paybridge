-- Double-entry ledger. Every money movement is a "journal": two or more lines that share a journal_key,
-- where the DEBIT lines add up to exactly the same as the CREDIT lines.
--
-- Account numbers here are the real 10-digit account numbers PLUS four outside-world accounts:
--   EXT:OPENING    money that already existed when this table was introduced
--   EXT:FUNDING    money added from outside (today: the dev "add test money" button)
--   EXT:IN_FLIGHT  payouts that are reserved but not yet confirmed by the provider
--   EXT:PAID_OUT   payouts the provider confirmed (money that left PayBridge)
-- For a PayBridge account: balance = total CREDITS - total DEBITS.
CREATE TABLE ledger_entries (
                                id              BIGSERIAL PRIMARY KEY,
                                journal_key     VARCHAR(80)   NOT NULL,
                                reference       VARCHAR(60),
                                account_number  VARCHAR(20)   NOT NULL,
                                direction       VARCHAR(6)    NOT NULL,
                                amount_kobo     BIGINT        NOT NULL,
                                entry_type      VARCHAR(20)   NOT NULL,
                                created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
                                CONSTRAINT ck_ledger_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
                                CONSTRAINT ck_ledger_amount CHECK (amount_kobo > 0),
    -- a journal can touch an account once per side: this stops the same journal being posted twice
                                CONSTRAINT uq_ledger_line UNIQUE (journal_key, account_number, direction)
);
CREATE INDEX ix_ledger_account ON ledger_entries (account_number);
CREATE INDEX ix_ledger_reference ON ledger_entries (reference);

-- Starting point for data that already exists: every account's current balance, and every payout that is
-- still PENDING (its money has left the customer's balance but has not reached anyone yet).
INSERT INTO ledger_entries (journal_key, reference, account_number, direction, amount_kobo, entry_type)
SELECT 'OPENING:' || account_number, NULL, account_number, 'CREDIT', balance_kobo, 'OPENING'
FROM accounts WHERE balance_kobo > 0;

INSERT INTO ledger_entries (journal_key, reference, account_number, direction, amount_kobo, entry_type)
SELECT 'OPENING:' || account_number, NULL, 'EXT:OPENING', 'DEBIT', balance_kobo, 'OPENING'
FROM accounts WHERE balance_kobo > 0;

INSERT INTO ledger_entries (journal_key, reference, account_number, direction, amount_kobo, entry_type)
SELECT 'OPENING_FLIGHT:' || reference, reference, 'EXT:IN_FLIGHT', 'CREDIT', total_debit_kobo, 'OPENING'
FROM transfers WHERE type = 'EXTERNAL' AND status = 'PENDING';

INSERT INTO ledger_entries (journal_key, reference, account_number, direction, amount_kobo, entry_type)
SELECT 'OPENING_FLIGHT:' || reference, reference, 'EXT:OPENING', 'DEBIT', total_debit_kobo, 'OPENING'
FROM transfers WHERE type = 'EXTERNAL' AND status = 'PENDING';

-- Append-only, like the audit log: no edits, no deletes. Mistakes are fixed with a new journal, never by changing history.
CREATE FUNCTION ledger_entries_block_changes() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_no_update
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_block_changes();