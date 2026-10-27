-- Money: DECIMAL(19,2). Rates: DECIMAL(7,4). All timestamps are UTC.

CREATE TABLE IF NOT EXISTS transactions (
    id             VARCHAR(64)   NOT NULL,
    type           VARCHAR(16)   NOT NULL,
    external_id    VARCHAR(128)  NOT NULL,
    currency       CHAR(3)       NOT NULL,
    occurred_at    DATETIME(3)   NOT NULL,
    total          DECIMAL(19,2) NOT NULL,
    metadata       JSON          NOT NULL,
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

-- Invoices: a frozen copy. The whole transaction (totals, tax/fee lines, metadata) is copied into the
-- snapshot JSON when the invoice is built, and nothing on the read path joins back to `transactions`.
-- transaction_id stays a real indexed column: it answers "is this transaction billed?" and, with the
-- RESTRICT foreign key, stops a billed transaction from being deleted even if application code is wrong.
CREATE TABLE IF NOT EXISTS invoices (
    id         VARCHAR(64)   NOT NULL,
    currency   CHAR(3)       NOT NULL,
    total      DECIMAL(19,2) NOT NULL,
    tax_rollup JSON          NOT NULL,
    fee_rollup JSON          NOT NULL,
    created_at DATETIME(3)   NOT NULL,
    updated_at DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_invoices_created (created_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS invoice_transactions (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    invoice_id     VARCHAR(64)  NOT NULL,
    position       INT          NOT NULL,
    transaction_id VARCHAR(64)  NOT NULL,
    snapshot       JSON         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_invoice_txn (invoice_id, transaction_id),
    KEY idx_invoice_txn_transaction (transaction_id),
    CONSTRAINT fk_invtxn_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id) ON DELETE CASCADE,
    CONSTRAINT fk_invtxn_txn FOREIGN KEY (transaction_id) REFERENCES transactions (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
