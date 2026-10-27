-- Money: DECIMAL(19,2). Rates: DECIMAL(7,4). All timestamps are UTC.

CREATE TABLE IF NOT EXISTS transactions (
    id             VARCHAR(64)   NOT NULL,
    type           VARCHAR(16)   NOT NULL,
    external_id    VARCHAR(128)  NOT NULL,
    currency       CHAR(3)       NOT NULL,
    occurred_at    DATETIME(3)   NOT NULL,
    total          DECIMAL(19,2) NOT NULL,
    metadata_extra TEXT          NULL,
    created_at     DATETIME(3)   NOT NULL,
    updated_at     DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_transactions_external_id (external_id),
    KEY idx_transactions_created (created_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS transaction_tax_lines (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    transaction_id VARCHAR(64)  NOT NULL,
    seq            INT          NOT NULL,
    name           VARCHAR(128) NOT NULL,
    rate           DECIMAL(7,4) NULL,
    amount         DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_txn_tax_txn (transaction_id, seq),
    CONSTRAINT fk_txn_tax_txn FOREIGN KEY (transaction_id) REFERENCES transactions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS transaction_fee_lines (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    transaction_id VARCHAR(64)  NOT NULL,
    seq            INT          NOT NULL,
    name           VARCHAR(128) NOT NULL,
    amount         DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_txn_fee_txn (transaction_id, seq),
    CONSTRAINT fk_txn_fee_txn FOREIGN KEY (transaction_id) REFERENCES transactions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS flight_details (
    transaction_id VARCHAR(64)   NOT NULL,
    origin         VARCHAR(64)   NOT NULL,
    destination    VARCHAR(64)   NOT NULL,
    fare           DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (transaction_id),
    CONSTRAINT fk_flight_txn FOREIGN KEY (transaction_id) REFERENCES transactions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS flight_coupons (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    transaction_id VARCHAR(64)  NOT NULL,
    seq            INT          NOT NULL,
    flight_number  VARCHAR(16)  NOT NULL,
    carrier        VARCHAR(16)  NULL,
    from_location  VARCHAR(64)  NOT NULL,
    to_location    VARCHAR(64)  NOT NULL,
    departure      DATETIME(3)  NOT NULL,
    arrival        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_flight_coupons_txn (transaction_id, seq),
    CONSTRAINT fk_flight_coupons_txn FOREIGN KEY (transaction_id) REFERENCES transactions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS hotel_details (
    transaction_id      VARCHAR(64)   NOT NULL,
    name                VARCHAR(255)  NOT NULL,
    street              VARCHAR(255)  NOT NULL,
    city                VARCHAR(128)  NOT NULL,
    country             VARCHAR(64)   NOT NULL,
    check_in            DATE          NOT NULL,
    check_out           DATE          NOT NULL,
    nights              INT           NOT NULL,
    room_rate_per_night DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (transaction_id),
    CONSTRAINT fk_hotel_txn FOREIGN KEY (transaction_id) REFERENCES transactions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS hotel_extras (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    transaction_id VARCHAR(64)   NOT NULL,
    seq            INT           NOT NULL,
    name           VARCHAR(128)  NOT NULL,
    amount         DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_hotel_extras_txn (transaction_id, seq),
    CONSTRAINT fk_hotel_extras_txn FOREIGN KEY (transaction_id) REFERENCES transactions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS rail_legs (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    transaction_id VARCHAR(64)  NOT NULL,
    seq            INT          NOT NULL,
    origin         VARCHAR(128) NOT NULL,
    destination    VARCHAR(128) NOT NULL,
    departure      DATETIME(3)  NOT NULL,
    arrival        DATETIME(3)  NOT NULL,
    train_number   VARCHAR(32)  NULL,
    travel_class   VARCHAR(32)  NULL,
    PRIMARY KEY (id),
    KEY idx_rail_legs_txn (transaction_id, seq),
    CONSTRAINT fk_rail_legs_txn FOREIGN KEY (transaction_id) REFERENCES transactions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS navan_fee_details (
    transaction_id      VARCHAR(64)  NOT NULL,
    fee_kind            VARCHAR(32)  NOT NULL,
    description         VARCHAR(255) NULL,
    related_external_id VARCHAR(128) NULL,
    PRIMARY KEY (transaction_id),
    CONSTRAINT fk_navan_fee_txn FOREIGN KEY (transaction_id) REFERENCES transactions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Invoices: a frozen copy. Content is copied; transaction_id is a reference only
-- (FK RESTRICT is a DB-level backstop: a billed transaction cannot be deleted).
CREATE TABLE IF NOT EXISTS invoices (
    id         VARCHAR(64)   NOT NULL,
    currency   CHAR(3)       NOT NULL,
    total      DECIMAL(19,2) NOT NULL,
    created_at DATETIME(3)   NOT NULL,
    updated_at DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_invoices_created (created_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS invoice_transactions (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_id    VARCHAR(64)   NOT NULL,
    position      INT           NOT NULL,
    transaction_id VARCHAR(64)  NOT NULL,
    type          VARCHAR(16)   NOT NULL,
    external_id   VARCHAR(128)  NOT NULL,
    currency      CHAR(3)       NOT NULL,
    occurred_at   DATETIME(3)   NOT NULL,
    total         DECIMAL(19,2) NOT NULL,
    metadata_json LONGTEXT      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_invoice_txn (invoice_id, transaction_id),
    KEY idx_invoice_txn_transaction (transaction_id),
    CONSTRAINT fk_invtxn_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id) ON DELETE CASCADE,
    CONSTRAINT fk_invtxn_txn FOREIGN KEY (transaction_id) REFERENCES transactions (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS invoice_tax_lines (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_transaction_id BIGINT        NOT NULL,
    seq                    INT           NOT NULL,
    name                   VARCHAR(128)  NOT NULL,
    rate                   DECIMAL(7,4)  NULL,
    amount                 DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_inv_tax_invtxn (invoice_transaction_id, seq),
    CONSTRAINT fk_inv_tax_invtxn FOREIGN KEY (invoice_transaction_id) REFERENCES invoice_transactions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS invoice_fee_lines (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_transaction_id BIGINT        NOT NULL,
    seq                    INT           NOT NULL,
    name                   VARCHAR(128)  NOT NULL,
    amount                 DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_inv_fee_invtxn (invoice_transaction_id, seq),
    CONSTRAINT fk_inv_fee_invtxn FOREIGN KEY (invoice_transaction_id) REFERENCES invoice_transactions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS invoice_tax_rollup (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_id VARCHAR(64)   NOT NULL,
    seq        INT           NOT NULL,
    name       VARCHAR(128)  NOT NULL,
    rate       DECIMAL(7,4)  NULL,
    amount     DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_inv_tax_rollup (invoice_id, seq),
    CONSTRAINT fk_inv_tax_rollup FOREIGN KEY (invoice_id) REFERENCES invoices (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS invoice_fee_rollup (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_id VARCHAR(64)   NOT NULL,
    seq        INT           NOT NULL,
    name       VARCHAR(128)  NOT NULL,
    amount     DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_inv_fee_rollup (invoice_id, seq),
    CONSTRAINT fk_inv_fee_rollup FOREIGN KEY (invoice_id) REFERENCES invoices (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
