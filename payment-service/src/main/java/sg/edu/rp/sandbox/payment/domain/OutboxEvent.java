package sg.edu.rp.sandbox.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Transactional-outbox row staged by payment-service within a transfer's
 * transaction. One row per completed transfer; consumers poll it read-only.
 *
 * <p>Column mapping matches {@code V3__create_event_outbox.sql} exactly so
 * {@code ddl-auto=validate} passes. {@code payload} is a JSON string stored in a
 * portable {@code LONGTEXT} column (mapped as a plain {@code String}), not a
 * native JSON type, so it behaves identically on H2 (MySQL mode) and MySQL 8.
 */
@Entity
@Table(name = "event_outbox")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "transaction_ref", nullable = false)
    private String transactionRef;

    // Mapped as plain String (not @Lob/CLOB): H2 in MySQL mode reports LONGTEXT as
    // character varying, so a String column type aligns with ddl-auto=validate on
    // both H2 and MySQL. columnDefinition pins the DDL type for any JPA-generated DDL.
    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(String eventType, String transactionRef, String payload,
                       Instant occurredAt, Instant createdAt) {
        this.eventType = eventType;
        this.transactionRef = transactionRef;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTransactionRef() {
        return transactionRef;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
