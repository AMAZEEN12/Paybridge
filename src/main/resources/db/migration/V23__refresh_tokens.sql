-- Refresh tokens for maintaining customer sessions during long transactions
CREATE TABLE refresh_tokens (
    id            BIGSERIAL PRIMARY KEY,
    customer_id   BIGINT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    token_hash    VARCHAR(64) NOT NULL UNIQUE,
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at    TIMESTAMP WITH TIME ZONE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_refresh_tokens_customer ON refresh_tokens (customer_id);
CREATE INDEX ix_refresh_tokens_hash ON refresh_tokens (token_hash);
