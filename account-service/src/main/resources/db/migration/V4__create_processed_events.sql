-- Per-service idempotency guard. transaction_ref is the natural primary key:
-- a duplicate insert is rejected, so a given outbox event's balance delta is
-- applied exactly once even under redelivery or overlapping poll ticks.
CREATE TABLE processed_events (
    transaction_ref VARCHAR(100) NOT NULL,
    processed_at    TIMESTAMP    NOT NULL,
    PRIMARY KEY (transaction_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
