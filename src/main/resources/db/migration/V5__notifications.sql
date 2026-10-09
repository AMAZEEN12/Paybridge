CREATE TABLE notifications (
    id           BIGSERIAL PRIMARY KEY,
    customer_id  BIGINT        NOT NULL,
    type         VARCHAR(30)   NOT NULL,
    title        VARCHAR(100)  NOT NULL,
    message      VARCHAR(400)  NOT NULL,
    reference    VARCHAR(60),
    read_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_customer ON notifications (customer_id, created_at DESC);
