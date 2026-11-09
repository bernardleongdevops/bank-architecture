package sg.edu.rp.sandbox.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Idempotency marker owned by transaction-service. The {@code transactionRef} of
 * a processed outbox row is its natural primary key, so re-inserting an
 * already-present ref is rejected by the database and the poller treats the row
 * as already done.
 *
 * <p>Column mapping matches {@code V3__create_processed_events.sql} exactly so
 * {@code ddl-auto=validate} passes.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "transaction_ref", nullable = false)
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
