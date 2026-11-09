package sg.edu.rp.sandbox.transaction.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Idempotency store for transaction-service. Keyed by {@code transactionRef}
 * (the natural PK), so {@code existsById} drives the skip check and the unique
 * insert enforces exactly-once ledger application.
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
