package sg.edu.rp.sandbox.payment.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.edu.rp.sandbox.common.aml.AmlDecision;
import sg.edu.rp.sandbox.common.aml.AmlProvider;
import sg.edu.rp.sandbox.common.aml.AmlRequest;
import sg.edu.rp.sandbox.common.aml.AmlResult;
import sg.edu.rp.sandbox.common.error.BusinessException;
import sg.edu.rp.sandbox.common.event.EventPublisher;
import sg.edu.rp.sandbox.common.event.PaymentCompletedEvent;
import sg.edu.rp.sandbox.payment.domain.Account;
import sg.edu.rp.sandbox.payment.domain.AccountRepository;
import sg.edu.rp.sandbox.payment.domain.LedgerEntry;
import sg.edu.rp.sandbox.payment.domain.LedgerEntryRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Internal account-to-account transfer.
 *
 * <p>Steps, all inside one transaction so money never half-moves:
 * <ol>
 *   <li>validate ownership (source must belong to the caller) and sufficient funds;</li>
 *   <li>screen via the pluggable {@link AmlProvider} (FLAGGED blocks, REVIEW holds);</li>
 *   <li>debit the source, credit the destination, and append two balanced ledger legs;</li>
 *   <li>publish a {@link PaymentCompletedEvent} via the pluggable {@link EventPublisher}.</li>
 * </ol>
 */
@Service
public class PaymentService {

    private final AccountRepository accounts;
    private final LedgerEntryRepository ledger;
    private final AmlProvider amlProvider;
    private final EventPublisher eventPublisher;

    public PaymentService(AccountRepository accounts, LedgerEntryRepository ledger,
                          AmlProvider amlProvider, EventPublisher eventPublisher) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.amlProvider = amlProvider;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public TransferResult transfer(String customerRef, String fromAccountNumber,
                                   String toAccountNumber, BigDecimal amount, String description) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "Transfer amount must be positive");
        }
        if (fromAccountNumber.equals(toAccountNumber)) {
            throw new BusinessException("SAME_ACCOUNT", "Source and destination must differ");
        }

        Account from = accounts.findByAccountNumber(fromAccountNumber)
                .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                        "No source account " + fromAccountNumber));
        Account to = accounts.findByAccountNumber(toAccountNumber)
                .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                        "No destination account " + toAccountNumber));

        // Ownership: the caller must own the source account.
        if (!from.getCustomerRef().equals(customerRef)) {
            throw new BusinessException("NOT_ACCOUNT_OWNER",
                    "Source account does not belong to the current customer");
        }
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new BusinessException("CURRENCY_MISMATCH", "Accounts have different currencies");
        }
        if (from.getBalance().compareTo(amount) < 0) {
            throw new BusinessException("INSUFFICIENT_FUNDS", "Insufficient funds in source account");
        }

        // Pluggable AML screen.
        AmlResult aml = amlProvider.screen(
                new AmlRequest(fromAccountNumber, toAccountNumber, amount, from.getCurrency()));
        if (aml.decision() == AmlDecision.FLAGGED) {
            throw new BusinessException("AML_BLOCKED", "Transfer blocked by AML: " + aml.reason());
        }
        if (aml.decision() == AmlDecision.REVIEW) {
            throw new BusinessException("AML_HELD", "Transfer held for AML review: " + aml.reason());
        }

        // Move the money atomically and write the two balanced ledger legs.
        String txnRef = "TXN-" + UUID.randomUUID();
        from.debit(amount);
        to.credit(amount);
        accounts.save(from);
        accounts.save(to);

        ledger.save(new LedgerEntry(txnRef, fromAccountNumber, from.getCustomerRef(),
                "DEBIT", amount, from.getCurrency(), description, toAccountNumber));
        ledger.save(new LedgerEntry(txnRef, toAccountNumber, to.getCustomerRef(),
                "CREDIT", amount, to.getCurrency(), description, fromAccountNumber));

        // Publish the completion event. The customer refs come from the already-loaded
        // account entities (no cross-schema read) so downstream consumers can populate
        // their NOT NULL customer_ref columns. When provider.events=outbox this insert
        // joins the transfer's @Transactional, so the event commits atomically with the
        // money movement.
        eventPublisher.publish(new PaymentCompletedEvent(
                txnRef, fromAccountNumber, toAccountNumber,
                from.getCustomerRef(), to.getCustomerRef(),
                amount, from.getCurrency(), Instant.now()));

        return new TransferResult(txnRef, "COMPLETED", fromAccountNumber, toAccountNumber,
                amount, from.getCurrency(), aml.reason());
    }
}
