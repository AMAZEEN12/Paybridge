CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    actor       VARCHAR(60)   NOT NULL,
    action      VARCHAR(60)   NOT NULL,
    reference   VARCHAR(60),
    request_id  VARCHAR(40),
    details     VARCHAR(1000)
);
CREATE INDEX ix_audit_reference ON audit_log (reference, id);
CREATE INDEX ix_audit_action ON audit_log (action, created_at);

-- The audit log is append-only: no edits, no deletes, not even by the application.
CREATE FUNCTION audit_log_block_changes() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_no_update
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_block_changes();
