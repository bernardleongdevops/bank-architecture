package sg.edu.rp.sandbox.transaction.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.edu.rp.sandbox.common.event.PaymentCompletedEvent;
import sg.edu.rp.sandbox.transaction.domain.Direction;
import sg.edu.rp.sandbox.transaction.domain.LedgerEntry;
import sg.edu.rp.sandbox.transaction.domain.ProcessedEvent;
import sg.edu.rp.sandbox.transaction.domain.ProcessedEventRepository;
import sg.edu.rp.sandbox.transaction.service.TransactionService;

import java.time.Instant;
import java.util.List;

/**
 * Applies a single outbox row to the ledger in one local transaction.
 *
 * <p>Kept in its own bean (rather than a method on {@link TransactionOutboxPoller})
 * so the {@code @Transactional} proxy is honored: the poller invokes this through
 * Spring, giving each row an independent commit boundary. The two ledger legs and
 * the {@code processed_events} marker are written in the SAME transaction, so they
 * commit together or not at all; if anything fails, nothing is marked processed and
 * the row is retried on a later poll pass.
 */
@Component
public class OutboxRowProcessor {

    private static final String DESCRIPTION = "Transfer";

    private final ProcessedEventRepository processedEventRepository;
    private final TransactionService transactionService;
    private final ObjectMapper objectMapper;

    public OutboxRowProcessor(ProcessedEventRepository processedEventRepository,
                              TransactionService transactionService,
                              ObjectMapper objectMapper) {
        this.processedEventRepository = processedEventRepository;
        this.transactionService = transactionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Deserializes the payload and appends the DEBIT (source) and CREDIT
     * (destination) legs, then records the idempotency marker, all atomically.
     * {@code REQUIRES_NEW} gives each row its own transaction even though the
     * caller polls outside a transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void apply(OutboxEvent row) throws Exception {
        PaymentCompletedEvent evt =
                objectMapper.readValue(row.getPayload(), PaymentCompletedEvent.class);

        LedgerEntry debitLeg = new LedgerEntry(
                evt.transactionRef(), evt.fromAccount(), evt.fromCustomerRef(),
                Direction.DEBIT, evt.amount(), evt.currency(),
                DESCRIPTION, evt.toAccount(), evt.occurredAt());

        LedgerEntry creditLeg = new LedgerEntry(
                evt.transactionRef(), evt.toAccount(), evt.toCustomerRef(),
                Direction.CREDIT, evt.amount(), evt.currency(),
                DESCRIPTION, evt.fromAccount(), evt.occurredAt());

        transactionService.record(List.of(debitLeg, creditLeg));
        processedEventRepository.save(new ProcessedEvent(evt.transactionRef(), Instant.now()));
    }
}
