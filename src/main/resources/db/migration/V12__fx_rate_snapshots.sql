CREATE TABLE fx_rate_snapshots (
                                   id                 BIGSERIAL PRIMARY KEY,
                                   provider           VARCHAR(40) NOT NULL,
                                   base_currency      VARCHAR(3)  NOT NULL,
                                   provider_timestamp TIMESTAMPTZ NOT NULL,
                                   fetched_at         TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_fx_rate_snapshots_ts ON fx_rate_snapshots (provider_timestamp DESC);

CREATE TABLE fx_snapshot_rates (
                                   snapshot_id BIGINT         NOT NULL REFERENCES fx_rate_snapshots (id),
                                   currency    VARCHAR(3)     NOT NULL,
                                   rate        NUMERIC(24,12) NOT NULL CHECK (rate > 0),
                                   PRIMARY KEY (snapshot_id, currency)
);
