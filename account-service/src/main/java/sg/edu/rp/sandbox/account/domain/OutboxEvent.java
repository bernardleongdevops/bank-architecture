package sg.edu.rp.sandbox.account.domain;

import java.time.Instant;

/**
 * READ-ONLY view of the shared {@code event_outbox} table.
 *
 * <p>The outbox is written and owned exclusively by payment-service in its own
 * database ({@code sandbox_payment}). account-service only ever {@code SELECT}s
 * from it, via a cross-database read-only query (see {@link OutboxEventRepository}).
 *
 * <p><strong>Why this is a plain POJO and not a JPA {@code @Entity}:</strong> the
 * shared {@code event_outbox} does NOT live in this consumer's own database, so it
 * is not part of account-service's Flyway-managed schema. If it were mapped as a
 * managed {@code @Entity}, Hibernate's {@code ddl-auto=validate} would try to
 * validate a local {@code event_outbox} table in {@code sandbox_account} and fail
 * (there is none). Instead this is a lightweight row projection populated by a
 * native, schema-qualified {@code SELECT} (see {@link OutboxEventRepository}).
 * account-service therefore NEVER inserts, updates, or deletes the outbox.
 */
public class OutboxEvent {

    private final Long id;
    private final String eventType;
    private final String transactionRef;
    private final String payload;
    private final Instant occurredAt;
    private final Instant createdAt;

    public OutboxEvent(Long id,
                       String eventType,
                       String transactionRef,
                       String payload,
                       Instant occurredAt,
                       Instant createdAt) {
        this.id = id;
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
