package sg.edu.rp.sandbox.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import sg.edu.rp.sandbox.payment.domain.OutboxEvent;
import sg.edu.rp.sandbox.payment.domain.OutboxRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration + mapping validation for the outbox. With Flyway on H2 (MySQL mode)
 * and {@code ddl-auto=validate}, the {@link OutboxEvent} entity must line up with
 * {@code V3__create_event_outbox.sql} exactly (otherwise the context fails to
 * start). A saved row then round-trips every column.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OutboxMappingTest {

    @Autowired
    private OutboxRepository outbox;

    @Test
    void outboxEventRoundTripsAllColumns() {
        Instant occurred = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MILLIS);
        Instant created = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String payload = "{\"transactionRef\":\"TXN-ABC\",\"amount\":100.00}";

        OutboxEvent saved = outbox.save(
                new OutboxEvent("PaymentCompleted", "TXN-ABC", payload, occurred, created));

        assertThat(saved.getId()).isNotNull();

        OutboxEvent found = outbox.findById(saved.getId()).orElseThrow();
        assertThat(found.getEventType()).isEqualTo("PaymentCompleted");
        assertThat(found.getTransactionRef()).isEqualTo("TXN-ABC");
        assertThat(found.getPayload()).isEqualTo(payload);
        assertThat(found.getOccurredAt()).isEqualTo(occurred);
        assertThat(found.getCreatedAt()).isEqualTo(created);
    }
}
