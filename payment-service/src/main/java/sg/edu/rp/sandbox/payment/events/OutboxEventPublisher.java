package sg.edu.rp.sandbox.payment.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sg.edu.rp.sandbox.common.event.DomainEvent;
import sg.edu.rp.sandbox.common.event.EventPublisher;
import sg.edu.rp.sandbox.common.event.PaymentCompletedEvent;
import sg.edu.rp.sandbox.payment.domain.OutboxEvent;
import sg.edu.rp.sandbox.payment.domain.OutboxRepository;

import java.time.Instant;

/**
 * Transactional-outbox {@link EventPublisher}. Serializes a {@link DomainEvent}
 * to JSON and inserts exactly one {@link OutboxEvent} row.
 *
 * <p><b>Placement:</b> this lives in payment-service (not events-adapters)
 * because it depends on Spring Data JPA ({@link OutboxRepository}) and the
 * {@code event_outbox} mapping. Keeping it here avoids dragging JPA/Jackson
 * outbox beans into {@code events-adapters}, which every service imports - the
 * in-process publisher stays the untouched default for account/transaction
 * services. It is wired only when {@code provider.events=outbox}
 * (see {@code OutboxPublisherConfiguration}).
 *
 * <p>The save deliberately runs with <b>no</b> transaction annotation of its
 * own: it joins the caller's active {@code @Transactional} (the transfer), so
 * the outbox row commits atomically with the money movement - and rolls back
 * with it if the transfer fails.
 */
public class OutboxEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public OutboxEventPublisher(OutboxRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(DomainEvent event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize domain event " + event.eventType(), e);
        }

        String transactionRef = transactionRefOf(event);
        OutboxEvent row = new OutboxEvent(
                event.eventType(),
                transactionRef,
                json,
                event.occurredAt(),
                Instant.now());
        outbox.save(row); // joins the caller's active @Transactional; no new/REQUIRES_NEW txn
        log.info("Staged outbox event {} for {}", event.eventType(), transactionRef);
    }

    /**
     * {@link DomainEvent} does not expose a transactionRef, so read it from the
     * concrete event type. Today the only event is {@link PaymentCompletedEvent}.
     */
    private static String transactionRefOf(DomainEvent event) {
        if (event instanceof PaymentCompletedEvent completed) {
            return completed.transactionRef();
        }
        throw new IllegalArgumentException(
                "Unsupported event type for outbox: " + event.getClass().getName());
    }
}
