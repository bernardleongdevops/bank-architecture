package sg.edu.rp.sandbox.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Idempotency marker written in the same local transaction as a balance update.
 *
 * <p>{@code transactionRef} is the natural primary key, so inserting an
 * already-present ref is rejected by the database. That rejection rolls back the
 * enclosing poll transaction, leaving the balance unchanged and guaranteeing each
 * outbox event is applied exactly once.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "transaction_ref")
    private String transactionRef;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // for JPA
    }

    public ProcessedEvent(String transactionRef, Instant processedAt) {
        this.transactionRef = transactionRef;
        this.processedAt = processedAt;
    }

    public String getTransactionRef() {
        return transactionRef;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
