package sg.edu.rp.sandbox.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import sg.edu.rp.sandbox.account.domain.AccountRepository;
import sg.edu.rp.sandbox.account.domain.ProcessedEventRepository;
import sg.edu.rp.sandbox.account.outbox.AccountOutboxPoller;
import sg.edu.rp.sandbox.common.event.PaymentCompletedEvent;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Drives {@link AccountOutboxPoller} directly (no scheduler) against seeded
 * outbox rows on H2 (MySQL mode). Verifies conservation (net-zero across the
 * pair), idempotency (a second poll is a no-op), and that unknown accounts are
 * marked processed without error or balance change.
 *
 * <p>Seeded balances (V2): ACC-1001 = 5000.00, ACC-1002 = 1200.50.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8080/realms/digital-bank",
        // Push the scheduled poll far out so the background scheduler never races
        // the explicit poller.poll() calls in these tests (determinism).
        "outbox.poll-delay-ms=3600000"
})
class AccountOutboxPollerIntegrationTest {

    @TestConfiguration
    static class MockDecoderConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }

    @Autowired
    private AccountOutboxPoller poller;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanOutboxAndProcessed() {
        // Each test seeds its own outbox rows; keep these tables isolated.
        jdbcTemplate.update("DELETE FROM processed_events");
        jdbcTemplate.update("DELETE FROM event_outbox");
    }

    @AfterEach
    void restoreSeededState() {
        // The H2 test database is shared across test classes (DB_CLOSE_DELAY=-1)
        // and the @Scheduled poller is live, so balance mutations made here must
        // not leak into other tests. Clear the outbox/idempotency tables and
        // restore the seeded balances (V2) so other test classes see pristine data.
        jdbcTemplate.update("DELETE FROM event_outbox");
        jdbcTemplate.update("DELETE FROM processed_events");
        jdbcTemplate.update("UPDATE accounts SET balance = ? WHERE account_number = ?",
                new BigDecimal("5000.00"), "ACC-1001");
        jdbcTemplate.update("UPDATE accounts SET balance = ? WHERE account_number = ?",
                new BigDecimal("1200.50"), "ACC-1002");
        jdbcTemplate.update("UPDATE accounts SET balance = ? WHERE account_number = ?",
                new BigDecimal("8300.75"), "ACC-2001");
        jdbcTemplate.update("UPDATE accounts SET balance = ? WHERE account_number = ?",
                new BigDecimal("450.00"), "ACC-2002");
    }

    /** Inserts one PaymentCompleted outbox row with a JSON payload of the event. */
    private void seedOutboxRow(PaymentCompletedEvent event) throws Exception {
        String payload = objectMapper.writeValueAsString(event);
        jdbcTemplate.update(
                "INSERT INTO event_outbox (event_type, transaction_ref, payload, occurred_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                event.eventType(),
                event.transactionRef(),
                payload,
                Timestamp.from(event.occurredAt()),
                Timestamp.from(Instant.now()));
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }

    @Test
    void appliesDebitAndCreditOnceAndIsIdempotent() throws Exception {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                "TXN-OUTBOX-1", "ACC-1001", "ACC-1002",
                "CUST-ALICE", "CUST-ALICE",
                new BigDecimal("100.00"), "SGD", Instant.now());
        seedOutboxRow(event);

        poller.poll();

        // Conservation: ACC-1001 -100, ACC-1002 +100, net zero across the pair.
        assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("4900.00");
        assertThat(balanceOf("ACC-1002")).isEqualByComparingTo("1300.50");
        assertThat(processedEventRepository.existsById("TXN-OUTBOX-1")).isTrue();

        // Idempotency: a second poll over the same row changes nothing.
        poller.poll();
        assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("4900.00");
        assertThat(balanceOf("ACC-1002")).isEqualByComparingTo("1300.50");
    }

    @Test
    void unknownAccountIsMarkedProcessedWithNoBalanceChange() throws Exception {
        BigDecimal before1001 = balanceOf("ACC-1001");

        PaymentCompletedEvent event = new PaymentCompletedEvent(
                "TXN-OUTBOX-UNKNOWN", "ACC-1001", "ACC-9999",
                "CUST-ALICE", "CUST-GHOST",
                new BigDecimal("50.00"), "SGD", Instant.now());
        seedOutboxRow(event);

        poller.poll();

        // Known side (ACC-1001) is debited; unknown side (ACC-9999) is a no-op.
        // The row is still marked processed so it is never retried forever.
        assertThat(processedEventRepository.existsById("TXN-OUTBOX-UNKNOWN")).isTrue();
        assertThat(balanceOf("ACC-1001")).isEqualByComparingTo(before1001.subtract(new BigDecimal("50.00")));
    }

    @Test
    void bothAccountsUnknownIsMarkedProcessedWithNoError() throws Exception {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                "TXN-OUTBOX-ALL-UNKNOWN", "ACC-8888", "ACC-9999",
                "CUST-GHOST-1", "CUST-GHOST-2",
                new BigDecimal("25.00"), "SGD", Instant.now());
        seedOutboxRow(event);

        BigDecimal before1001 = balanceOf("ACC-1001");

        poller.poll();

        assertThat(processedEventRepository.existsById("TXN-OUTBOX-ALL-UNKNOWN")).isTrue();
        // No known account touched.
        assertThat(balanceOf("ACC-1001")).isEqualByComparingTo(before1001);
    }
}
