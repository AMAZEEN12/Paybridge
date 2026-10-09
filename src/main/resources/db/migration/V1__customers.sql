CREATE TABLE customers (
    id                    BIGSERIAL PRIMARY KEY,
    full_name             VARCHAR(120)  NOT NULL,
    email                 VARCHAR(160)  NOT NULL,
    password_hash         VARCHAR(100)  NOT NULL,
    pin_hash              VARCHAR(100),
    pin_failed_attempts   INT           NOT NULL DEFAULT 0,
    pin_locked_until      TIMESTAMPTZ,
    failed_login_attempts INT           NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_customers_email UNIQUE (email)
);
