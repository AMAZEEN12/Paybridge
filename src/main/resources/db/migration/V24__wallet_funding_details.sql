-- Account details issued per wallet so money can be received straight into it.
-- SANDBOX: the numbers are generated here, they are not real bank accounts.
CREATE TABLE wallet_funding_details (
                                        id             BIGSERIAL PRIMARY KEY,
                                        wallet_id      BIGINT       NOT NULL,
                                        bank_name      VARCHAR(80)  NOT NULL,
                                        bank_country   VARCHAR(2)   NOT NULL,
                                        account_number VARCHAR(34)  NOT NULL,
                                        routing_label  VARCHAR(30),
                                        routing_value  VARCHAR(20),
                                        iban           VARCHAR(34),
                                        swift_bic      VARCHAR(11),
                                        reference      VARCHAR(20)  NOT NULL,
                                        simulated      BOOLEAN      NOT NULL DEFAULT TRUE,
                                        created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                        CONSTRAINT fk_wfd_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id),
                                        CONSTRAINT uq_wfd_wallet UNIQUE (wallet_id),
                                        CONSTRAINT uq_wfd_number UNIQUE (bank_name, account_number),
                                        CONSTRAINT uq_wfd_reference UNIQUE (reference)
);