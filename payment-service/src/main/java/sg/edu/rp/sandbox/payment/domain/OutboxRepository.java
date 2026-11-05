package sg.edu.rp.sandbox.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for the payment-service transactional outbox. Writes happen inside
 * the transfer's own transaction so an outbox row commits atomically with the
 * money movement.
 */
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
}
