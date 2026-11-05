-- TEST-ONLY migration. Present only on the test classpath
-- (src/test/resources/db/migration), never in production (src/main/resources).
--
-- Deliberately numbered V900 (far above any production migration) so it can never
-- collide with a real consumer migration version.
--
-- Option A topology: in production the single shared event_outbox lives in
-- payment-service's database (sandbox_payment) and the consumers read it
-- cross-database via outbox.schema. There is intentionally NO event_outbox in
-- transaction-service's own (sandbox_transaction) database.
--
-- Under H2 tests, however, each service runs against one in-memory database and
-- the test seeds outbox rows directly, so we provide a seedable event_outbox in
-- the test schema (outbox.schema is overridden to PUBLIC in the test profile so
-- the read-only repository resolves PUBLIC.event_outbox). transaction-service
-- still only SELECTs this table; it never writes it at runtime.
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
