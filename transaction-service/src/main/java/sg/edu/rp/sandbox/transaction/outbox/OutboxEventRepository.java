package sg.edu.rp.sandbox.transaction.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Read-only access to the single shared {@code event_outbox} table, which lives in
 * payment-service's database (the {@code outbox.schema}, default
 * {@code sandbox_payment}) — NOT in this consumer's own database.
 *
 * <p><strong>Schema qualification (Option A):</strong> the read is issued as a
 * native {@code SELECT} against {@code <outbox.schema>.event_outbox}. Only this
 * outbox read crosses the database boundary; the consumer's own tables
 * ({@code ledger_entries} / {@code processed_events}) stay in
 * {@code sandbox_transaction} and are unaffected. We deliberately qualify ONLY this
 * query rather than setting a global {@code hibernate.default_schema} (which would
 * wrongly redirect the consumer's own tables too).
 *
 * <p>The schema name comes from the {@code outbox.schema} property and is validated
 * against {@code ^[A-Za-z0-9_]+$} before being interpolated, so it cannot be used
 * for SQL injection (it is an identifier, which cannot be a bind parameter).
 *
 * <p>Works identically on MySQL 8 and H2 (MySQL mode): both resolve a
 * {@code schema.table} qualifier within one connection. In tests the property is
 * pointed at the H2 test schema (e.g. {@code PUBLIC}), where a test-scoped
 * migration provides a seedable {@code event_outbox}.
 */
@Repository
public class OutboxEventRepository {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventRepository.class);
    private static final Pattern SCHEMA_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");

    private final JdbcTemplate jdbcTemplate;
    private final String schema;

    public OutboxEventRepository(JdbcTemplate jdbcTemplate,
                                 @Value("${outbox.schema:sandbox_payment}") String schema) {
        this.jdbcTemplate = jdbcTemplate;
        if (!SCHEMA_PATTERN.matcher(schema).matches()) {
            throw new IllegalArgumentException(
                    "Invalid outbox.schema '" + schema + "': must match " + SCHEMA_PATTERN.pattern());
        }
        this.schema = schema;
    }

    /**
     * Reads all outbox rows of the given type, oldest first (ascending id), from the
     * shared, payment-owned outbox.
     *
     * <p>Startup-order resilience: if payment-service has not yet created the shared
     * {@code event_outbox} (e.g. the consumer polls before the producer's migration
     * ran), the qualified SELECT fails with a "table doesn't exist" grammar error.
     * We log that at debug and return an empty list so this poll cycle is a no-op and
     * the service keeps running; a later tick succeeds once the table exists.
     */
    public List<OutboxEvent> findByEventTypeOrderByIdAsc(String eventType) {
        String sql = "SELECT id, event_type, transaction_ref, payload, occurred_at, created_at "
                + "FROM " + schema + ".event_outbox WHERE event_type = ? ORDER BY id ASC";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> new OutboxEvent(
                    rs.getLong("id"),
                    rs.getString("event_type"),
                    rs.getString("transaction_ref"),
                    rs.getString("payload"),
                    toInstant(rs.getTimestamp("occurred_at")),
                    toInstant(rs.getTimestamp("created_at"))), eventType);
        } catch (BadSqlGrammarException e) {
            // Shared outbox not created yet (producer startup lag): skip this cycle.
            log.debug("Shared outbox {}.event_outbox not available yet, skipping poll cycle: {}",
                    schema, e.getMessage());
            return List.of();
        } catch (DataAccessException e) {
            // Transient DB error: skip this cycle, retry on the next tick.
            log.warn("Failed to read shared outbox {}.event_outbox, skipping poll cycle: {}",
                    schema, e.getMessage());
            return List.of();
        }
    }

    private static Instant toInstant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
