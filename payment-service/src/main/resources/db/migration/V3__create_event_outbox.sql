-- Transactional Outbox: payment-service stages one row per completed transfer
-- inside the transfer's own transaction, so the event commits atomically with
-- the money movement. Consumers (account/transaction services) poll this table
-- read-only. payload is portable TEXT (LONGTEXT) JSON, not a native JSON type,
-- so H2 (MySQL mode) + Flyway + ddl-auto=validate behave identically to MySQL 8.
CREATE TABLE event_outbox (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    event_type      VARCHAR(100)  NOT NULL,
    transaction_ref VARCHAR(100)  NOT NULL,
    payload         LONGTEXT      NOT NULL,
    occurred_at     TIMESTAMP     NOT NULL,
    created_at      TIMESTAMP     NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_event_outbox_type (event_type, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
