package sg.edu.rp.sandbox.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import sg.edu.rp.sandbox.common.event.PaymentCompletedEvent;
import sg.edu.rp.sandbox.payment.domain.OutboxEvent;
import sg.edu.rp.sandbox.payment.domain.OutboxRepository;
import sg.edu.rp.sandbox.payment.events.OutboxEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OutboxEventPublisher} persists exactly one outbox row with the expected
 * columns, and the JSON payload deserializes back to an equal event (including
 * the new customer refs).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OutboxEventPublisherTest {

    @Autowired
    private OutboxRepository outbox;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void publishPersistsExactlyOneRowAndPayloadRoundTrips() throws Exception {
        Instant occurred = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                "TXN-XYZ", "ACC-1001", "ACC-1002", "CUST-ALICE", "CUST-ALICE",
                new BigDecimal("100.00"), "SGD", occurred);

        new OutboxEventPublisher(outbox, objectMapper).publish(event);

        // Exactly one row for this txn ref (assert on the ref, not the global count,
        // so the test is independent of any rows other tests may have left behind).
        List<OutboxEvent> rows = outbox.findAll().stream()
                .filter(e -> "TXN-XYZ".equals(e.getTransactionRef()))
                .toList();
        assertThat(rows).hasSize(1);

        OutboxEvent row = rows.get(0);
        assertThat(row.getEventType()).isEqualTo("PaymentCompleted");
        assertThat(row.getTransactionRef()).isEqualTo("TXN-XYZ");
        assertThat(row.getOccurredAt()).isEqualTo(occurred);
        assertThat(row.getCreatedAt()).isNotNull();

        PaymentCompletedEvent back = objectMapper.readValue(row.getPayload(), PaymentCompletedEvent.class);
        assertThat(back).isEqualTo(event);
        assertThat(back.fromCustomerRef()).isEqualTo("CUST-ALICE");
        assertThat(back.toCustomerRef()).isEqualTo("CUST-ALICE");
    }
}
