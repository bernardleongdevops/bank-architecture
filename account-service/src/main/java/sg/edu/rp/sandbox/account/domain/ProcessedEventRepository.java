package sg.edu.rp.sandbox.account.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Idempotency store keyed by {@code transactionRef}. {@code existsById} is the
 * skip check; {@code save} is the exactly-once guard (duplicate PK is rejected).
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
