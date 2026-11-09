package sg.edu.rp.sandbox.account.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sg.edu.rp.sandbox.account.domain.OutboxEvent;
import sg.edu.rp.sandbox.account.domain.OutboxEventRepository;
import sg.edu.rp.sandbox.common.event.PaymentCompletedEvent;

import java.util.List;

/**
 * Independent outbox consumer for account-service.
 *
 * <p>On each scheduled tick it reads every {@code PaymentCompleted} row from the
 * shared {@code event_outbox} (read-only, ordered by id) and applies the balance
 * effect of each row exactly once. Per-row work is delegated to
 * {@link AccountOutboxProcessor}, which runs in its own local transaction so that
 * one row's commit, no-op, or optimistic-lock rollback is independent of the rest.
 *
 * <p>Idempotency is guaranteed by the {@code processed_events} table: an
 * already-processed {@code transactionRef} is skipped; a newly applied one is
 * recorded in the same transaction as the balance update.
 */
@Component
public class AccountOutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(AccountOutboxPoller.class);
    private static final String EVENT_TYPE = "PaymentCompleted";

    private final OutboxEventRepository outboxRepository;
    private final AccountOutboxProcessor processor;
    private final ObjectMapper objectMapper;

    public AccountOutboxPoller(OutboxEventRepository outboxRepository,
                               AccountOutboxProcessor processor,
                               ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.processor = processor;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-delay-ms:1000}")
    public void poll() {
        List<OutboxEvent> rows = outboxRepository.findByEventTypeOrderByIdAsc(EVENT_TYPE);
        for (OutboxEvent row : rows) {
            try {
                PaymentCompletedEvent event =
                        objectMapper.readValue(row.getPayload(), PaymentCompletedEvent.class);
                processor.process(event);
            } catch (ObjectOptimisticLockingFailureException e) {
                // Stale @Version: balance and processed_events left unwritten; the
                // row stays unprocessed and is retried on the next poll tick.
                log.warn("Optimistic-lock conflict applying transfer {}, will retry next tick",
                        row.getTransactionRef());
            } catch (Exception e) {
                // Malformed payload or unexpected error: skip this row (do NOT mark
                // processed) so later rows still progress and this one can be retried.
                log.warn("Skipping outbox row id={} ref={}: {}",
                        row.getId(), row.getTransactionRef(), e.getMessage());
            }
        }
    }
}
