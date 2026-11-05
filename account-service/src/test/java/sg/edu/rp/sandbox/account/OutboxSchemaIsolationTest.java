package sg.edu.rp.sandbox.account;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for Option A (shared outbox location).
 *
 * <p>Asserts that account-service does NOT ship a production {@code event_outbox}
 * Flyway migration: the shared outbox is owned by payment-service and read
 * cross-database via {@code outbox.schema}. The only {@code event_outbox} DDL in
 * this module is the TEST-ONLY migration under {@code src/test/resources}. This
 * prevents a regression back to the "empty local copy" bug.
 */
class OutboxSchemaIsolationTest {

    @Test
    void noProductionEventOutboxMigrationExists() throws IOException {
        Path prodMigrations = Path.of("src/main/resources/db/migration");
        assertThat(Files.isDirectory(prodMigrations)).isTrue();

        try (Stream<Path> files = Files.list(prodMigrations)) {
            List<String> outboxMigrations = files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.toLowerCase().contains("event_outbox"))
                    .toList();
            assertThat(outboxMigrations)
                    .as("account-service must not create a local event_outbox table in "
                            + "production; it reads the shared sandbox_payment.event_outbox")
                    .isEmpty();
        }
    }

    @Test
    void testOnlyEventOutboxMigrationIsPresentForSeeding() throws IOException {
        Path testMigrations = Path.of("src/test/resources/db/migration");
        assertThat(Files.isDirectory(testMigrations)).isTrue();

        try (Stream<Path> files = Files.list(testMigrations)) {
            List<String> outboxMigrations = files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.toLowerCase().contains("event_outbox"))
                    .toList();
            assertThat(outboxMigrations)
                    .as("a test-only event_outbox migration must exist so H2 tests can seed it")
                    .isNotEmpty();
        }
    }
}
