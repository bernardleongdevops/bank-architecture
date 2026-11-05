package sg.edu.rp.sandbox.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import sg.edu.rp.sandbox.common.event.PaymentCompletedEvent;
import sg.edu.rp.sandbox.transaction.domain.Direction;
import sg.edu.rp.sandbox.transaction.domain.LedgerEntry;
import sg.edu.rp.sandbox.transaction.domain.LedgerEntryRepository;
import sg.edu.rp.sandbox.transaction.outbox.TransactionOutboxPoller;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the TransactionOutboxPoller projects a completed-transfer outbox row
 * into exactly two balanced ledger legs, idempotently (redelivery appends none),
 * with both customer refs populated and history ordered newest-first.
 *
 * <p>Outbox rows are inserted via raw SQL to simulate payment-service writing the
 * shared event_outbox; transaction-service only ever reads it.
 *
 * <p>Property 2 (Idempotency): applying a row once equals applying it N times.
 * Property 4 (Payload completeness): every appended leg has a non-null customerRef.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8080/realms/digital-bank",
        // Large delay so only the explicit poll() calls in the test drive processing.
        "outbox.poll-delay-ms=3600000"
})
class TransactionOutboxPollerIntegrationTest {

    @TestConfiguration
    static class MockDecoderConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return org.mockito.Mockito.mock(JwtDecoder.class);
        }
    }

    @Autowired
    private TransactionOutboxPoller poller;

    @Autowired
    private LedgerEntryRepository ledgerRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor asCustomer(String ref) {
        return jwt().jwt(j -> j.subject("sub-" + ref).claim("customer_ref", ref));
    }

    private void insertOutboxRow(PaymentCompletedEvent event) throws Exception {
        String payload = objectMapper.writeValueAsString(event);
        jdbcTemplate.update("""
                        INSERT INTO event_outbox
                            (event_type, transaction_ref, payload, occurred_at, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                event.eventType(),
                event.transactionRef(),
                payload,
                Timestamp.from(event.occurredAt()),
                Timestamp.from(Instant.now()));
    }

    @Test
    void pollProjectsOutboxRowIntoTwoBalancedLegsExactlyOnce() throws Exception {
        String ref = "TXN-TEST-1";
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                ref, "ACC-1001", "ACC-1002", "CUST-ALICE", "CUST-ALICE",
                new BigDecimal("100.00"), "SGD", occurredAt);
        insertOutboxRow(event);

        poller.poll();

        List<LedgerEntry> legs = ledgerRepository.findAll().stream()
                .filter(e -> ref.equals(e.getTransactionRef()))
                .toList();
        assertThat(legs).hasSize(2);

        LedgerEntry debit = legs.stream().filter(e -> e.getDirection() == Direction.DEBIT)
                .findFirst().orElseThrow();
        LedgerEntry credit = legs.stream().filter(e -> e.getDirection() == Direction.CREDIT)
                .findFirst().orElseThrow();

        // DEBIT leg = source account.
        assertThat(debit.getAccountNumber()).isEqualTo("ACC-1001");
        assertThat(debit.getCustomerRef()).isEqualTo("CUST-ALICE");
        assertThat(debit.getCounterparty()).isEqualTo("ACC-1002");
        assertThat(debit.getAmount()).isEqualByComparingTo("100.00");
        assertThat(debit.getCurrency()).isEqualTo("SGD");
        assertThat(debit.getBookedAt()).isEqualTo(occurredAt);

        // CREDIT leg = destination account.
        assertThat(credit.getAccountNumber()).isEqualTo("ACC-1002");
        assertThat(credit.getCustomerRef()).isEqualTo("CUST-ALICE");
        assertThat(credit.getCounterparty()).isEqualTo("ACC-1001");
        assertThat(credit.getAmount()).isEqualByComparingTo("100.00");
        assertThat(credit.getBookedAt()).isEqualTo(occurredAt);

        // Property 4: every appended leg carries a non-null customerRef.
        assertThat(legs).allMatch(e -> e.getCustomerRef() != null);

        // Property 2: re-polling the same row appends no further legs.
        poller.poll();
        long legCountAfterRedelivery = ledgerRepository.findAll().stream()
                .filter(e -> ref.equals(e.getTransactionRef()))
                .count();
        assertThat(legCountAfterRedelivery).isEqualTo(2);
    }

    @Test
    void pollProjectsRealisticTxnUuidReferenceWithoutTruncation() throws Exception {
        // Real transfer refs are "TXN-" + UUID = 40 chars, which overflowed the
        // original VARCHAR(36) column (V4 widens it to VARCHAR(50)). Short refs like
        // "TXN-TEST-1" fit in 36 and hid the regression, so this case uses a full
        // 40-char reference to guard against the VARCHAR(36) truncation.
        String ref = "TXN-" + UUID.randomUUID();
        assertThat(ref).hasSize(40);

        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                ref, "ACC-1001", "ACC-1002", "CUST-ALICE", "CUST-ALICE",
                new BigDecimal("55.00"), "SGD", occurredAt);
        insertOutboxRow(event);

        poller.poll();

        // Both legs must persist for the full 40-char ref (would fail on VARCHAR(36)).
        List<LedgerEntry> legs = ledgerRepository.findAll().stream()
                .filter(e -> ref.equals(e.getTransactionRef()))
                .toList();
        assertThat(legs).hasSize(2);
        assertThat(legs).extracting(LedgerEntry::getDirection)
                .containsExactlyInAnyOrder(Direction.DEBIT, Direction.CREDIT);

        // history(...) returns both legs for the source account under this ref.
        List<LedgerEntry> history =
                ledgerRepository.findHistory("ACC-1001", "CUST-ALICE", null, null);
        List<LedgerEntry> appended = history.stream()
                .filter(e -> ref.equals(e.getTransactionRef()))
                .toList();
        assertThat(appended).hasSize(1); // ACC-1001 is the DEBIT (source) leg
        assertThat(appended.get(0).getTransactionRef()).isEqualTo(ref);
    }

    @Test
    void historyReturnsProjectedLegsNewestFirst() throws Exception {
        // Two transfers for ACC-1001 (CUST-ALICE) at different times; newer must come first.
        Instant older = Instant.parse("2027-01-01T10:00:00Z");
        Instant newer = Instant.parse("2027-02-01T10:00:00Z");
        insertOutboxRow(new PaymentCompletedEvent(
                "TXN-ORDER-OLD", "ACC-1001", "ACC-1002", "CUST-ALICE", "CUST-ALICE",
                new BigDecimal("10.00"), "SGD", older));
        insertOutboxRow(new PaymentCompletedEvent(
                "TXN-ORDER-NEW", "ACC-1001", "ACC-1002", "CUST-ALICE", "CUST-ALICE",
                new BigDecimal("20.00"), "SGD", newer));

        poller.poll();

        // GET returns ACC-1001 legs ordered bookedAt desc, id desc. Filter to the two
        // refs appended here and assert the newer transfer precedes the older one.
        mockMvc.perform(get("/transactions/accounts/ACC-1001").with(asCustomer("CUST-ALICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.transactionRef=='TXN-ORDER-NEW')]").exists())
                .andExpect(jsonPath("$[?(@.transactionRef=='TXN-ORDER-OLD')]").exists());

        // Direct repository check of ordering for the projected legs.
        List<LedgerEntry> history = ledgerRepository.findHistory("ACC-1001", "CUST-ALICE", null, null);
        List<LedgerEntry> appended = history.stream()
                .filter(e -> e.getTransactionRef().startsWith("TXN-ORDER-"))
                .toList();
        assertThat(appended).hasSize(2);
        assertThat(appended.get(0).getTransactionRef()).isEqualTo("TXN-ORDER-NEW");
        assertThat(appended.get(1).getTransactionRef()).isEqualTo("TXN-ORDER-OLD");
        // Global ordering invariant holds across the whole result.
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i - 1).getBookedAt())
                    .isAfterOrEqualTo(history.get(i).getBookedAt());
        }
    }
}
