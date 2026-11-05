package sg.edu.rp.sandbox.account.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sg.edu.rp.sandbox.account.domain.Account;
import sg.edu.rp.sandbox.account.domain.AccountRepository;
import sg.edu.rp.sandbox.account.domain.ProcessedEvent;
import sg.edu.rp.sandbox.account.domain.ProcessedEventRepository;
import sg.edu.rp.sandbox.common.event.PaymentCompletedEvent;

import java.time.Instant;
import java.util.Optional;

/**
 * Applies a single {@link PaymentCompletedEvent}'s balance effect in its own
 * local transaction, so each outbox row commits (or rolls back) independently.
 *
 * <p>Within the transaction: skip if already processed; otherwise debit the
 * source account and credit the destination account when they exist in this
 * schema (unknown accounts are a no-op, not an error), then record the
 * {@code transactionRef} in {@code processed_events}. The balance writes and the
 * {@code processed_events} insert share one transaction, so either all commit or
 * none do. A stale {@code @Version} surfaces as an optimistic-lock failure that
 * rolls the whole transaction back, leaving the row unprocessed for a later tick.
 */
@Component
public class AccountOutboxProcessor {

    private final AccountRepository accountRepository;
    private final ProcessedEventRepository processedEventRepository;

    public AccountOutboxProcessor(AccountRepository accountRepository,
                                  ProcessedEventRepository processedEventRepository) {
        this.accountRepository = accountRepository;
        this.processedEventRepository = processedEventRepository;
    }

    @Transactional
    public void process(PaymentCompletedEvent event) {
        String transactionRef = event.transactionRef();
        if (processedEventRepository.existsById(transactionRef)) {
            return; // idempotent skip
        }

        Optional<Account> from = accountRepository.findByAccountNumber(event.fromAccount());
        Optional<Account> to = accountRepository.findByAccountNumber(event.toAccount());

        from.ifPresent(account -> {
            account.debit(event.amount());
            accountRepository.save(account);
        });
        to.ifPresent(account -> {
            account.credit(event.amount());
            accountRepository.save(account);
        });

        // Mark processed in the SAME transaction as the balance update. Unknown
        // accounts still get marked (no-op) so the row is not retried forever.
        processedEventRepository.save(new ProcessedEvent(transactionRef, Instant.now()));
    }
}
