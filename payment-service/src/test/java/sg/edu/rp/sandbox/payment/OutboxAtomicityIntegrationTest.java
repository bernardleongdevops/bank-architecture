package sg.edu.rp.sandbox.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import sg.edu.rp.sandbox.common.error.BusinessException;

import static org.mockito.Mockito.mock;
import sg.edu.rp.sandbox.compliance.ComplianceOverrides;
import sg.edu.rp.sandbox.payment.domain.AccountRepository;
import sg.edu.rp.sandbox.payment.domain.OutboxRepository;
import sg.edu.rp.sandbox.payment.service.PaymentService;
import sg.edu.rp.sandbox.payment.service.TransferResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property 1: Atomicity (producer). With {@code provider.events=outbox}, an
 * {@code event_outbox} row exists <b>iff</b> the transfer committed.
 *
 * <ul>
 *   <li>A successful transfer leaves exactly one outbox row for its txn ref.</li>
 *   <li>An AML-blocked transfer rolls back: zero outbox rows, no money moved.</li>
 *   <li>An insufficient-funds transfer rolls back: zero outbox rows, no money moved.</li>
 * </ul>
 *
 * The service is invoked directly (no HTTP/security needed) to assert the
 * transactional boundary around the outbox insert.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "provider.events=outbox",
        // Isolated in-memory DB: this test commits real transfers (not rolled back),
        // so it must not pollute the shared jdbc:h2:mem:payment used by other tests.
        "spring.datasource.url=jdbc:h2:mem:payment_outbox_atomicity;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8080/realms/digital-bank"
})
class OutboxAtomicityIntegrationTest {

    @TestConfiguration
    static class TestBeans {
        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private ComplianceOverrides overrides;

    @AfterEach
    void tearDown() {
        overrides.clear();
    }

    @Test
    void successfulTransferStagesExactlyOneOutboxRow() {
        long before = outbox.count();
        BigDecimal fromBefore = balanceOf("ACC-1001");
        BigDecimal toBefore = balanceOf("ACC-1002");

        TransferResult result = paymentService.transfer(
                "CUST-ALICE", "ACC-1001", "ACC-1002", new BigDecimal("100.00"), "rent");

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(outbox.count()).isEqualTo(before + 1);
        assertThat(outbox.findAll()).anyMatch(e ->
                e.getTransactionRef().equals(result.transactionRef())
                        && e.getEventType().equals("PaymentCompleted"));

        assertThat(balanceOf("ACC-1001")).isEqualByComparingTo(fromBefore.subtract(new BigDecimal("100.00")));
        assertThat(balanceOf("ACC-1002")).isEqualByComparingTo(toBefore.add(new BigDecimal("100.00")));
    }

    @Test
    void amlBlockedTransferLeavesNoOutboxRowAndNoMovement() {
        // Lower the flag threshold so an affordable amount is FLAGGED (blocked).
        overrides.setAmlFlagThresholdOverride(new BigDecimal("50.00"));

        long before = outbox.count();
        BigDecimal fromBefore = balanceOf("ACC-1001");
        BigDecimal toBefore = balanceOf("ACC-1002");

        assertThatThrownBy(() -> paymentService.transfer(
                "CUST-ALICE", "ACC-1001", "ACC-1002", new BigDecimal("100.00"), "blocked"))
                .isInstanceOf(BusinessException.class);

        assertThat(outbox.count()).isEqualTo(before);
        assertThat(balanceOf("ACC-1001")).isEqualByComparingTo(fromBefore);
        assertThat(balanceOf("ACC-1002")).isEqualByComparingTo(toBefore);
    }

    @Test
    void insufficientFundsTransferLeavesNoOutboxRowAndNoMovement() {
        long before = outbox.count();
        BigDecimal fromBefore = balanceOf("ACC-1002");
        BigDecimal toBefore = balanceOf("ACC-1001");

        assertThatThrownBy(() -> paymentService.transfer(
                "CUST-ALICE", "ACC-1002", "ACC-1001", new BigDecimal("999999.00"), "too big"))
                .isInstanceOf(BusinessException.class);

        assertThat(outbox.count()).isEqualTo(before);
        assertThat(balanceOf("ACC-1002")).isEqualByComparingTo(fromBefore);
        assertThat(balanceOf("ACC-1001")).isEqualByComparingTo(toBefore);
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accounts.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }
}
