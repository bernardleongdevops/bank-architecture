-- Per-consumer idempotency guard for transaction-service. Each completed-transfer
-- outbox row is applied to the ledger exactly once: the poller records the
-- transaction_ref here within the same local transaction as the two ledger legs.
-- The natural PK on transaction_ref makes a duplicate insert fail, so a redelivered
-- outbox row is skipped and never appends a second pair of legs.
CREATE TABLE processed_events (
    transaction_ref VARCHAR(100) NOT NULL,
    processed_at    TIMESTAMP    NOT NULL,
    PRIMARY KEY (transaction_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
