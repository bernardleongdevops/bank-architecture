package sg.edu.rp.sandbox.transaction.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sg.edu.rp.sandbox.transaction.domain.ProcessedEventRepository;

import java.util.List;

/**
 * Reads unprocessed {@code PaymentCompleted} rows from the shared, read-only
 * {@code event_outbox} and projects each one into two statement-history legs
 * (a DEBIT on the source account and a CREDIT on the destination), exactly once.
 *
 * <p>Idempotency and atomicity: each row is applied in its own local transaction
 * by {@link OutboxRowProcessor} (a separate bean so the {@code @Transactional}
 * proxy applies). The two ledger legs and the {@code processed_events} marker
 * commit together or not at all, so a redelivered row is skipped (its
 * {@code transactionRef} is already present) and never produces a second pair of
 * legs. The poller only SELECTs the outbox; it never writes it.
 */
@Component
public class TransactionOutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(TransactionOutboxPoller.class);
    private static final String EVENT_TYPE = "PaymentCompleted";

    private final OutboxEventRepository outboxRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxRowProcessor rowProcessor;

    public TransactionOutboxPoller(OutboxEventRepository outboxRepository,
                                   ProcessedEventRepository processedEventRepository,
                                   OutboxRowProcessor rowProcessor) {
        this.outboxRepository = outboxRepository;
        this.processedEventRepository = processedEventRepository;
        this.rowProcessor = rowProcessor;
    }

    /**
     * One poll pass: read outbox rows in id order and apply each unseen one.
     * A failure on one row (deserialization error, duplicate marker) is logged
     * and skipped so it never blocks later rows.
     */
    @Scheduled(fixedDelayString = "${outbox.poll-delay-ms:1000}")
    public void poll() {
        List<OutboxEvent> rows = outboxRepository.findByEventTypeOrderByIdAsc(EVENT_TYPE);
        for (OutboxEvent row : rows) {
            if (processedEventRepository.existsById(row.getTransactionRef())) {
                continue; // idempotent skip
            }
            try {
                rowProcessor.apply(row);
            } catch (Exception ex) {
                // Do not mark processed: the row is retried on a later tick. Must not
                // block subsequent rows in this pass.
                log.warn("Skipping outbox row id={} transactionRef={}: {}",
                        row.getId(), row.getTransactionRef(), ex.getMessage());
            }
        }
    }
}
