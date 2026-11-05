package sg.edu.rp.sandbox.ai.banking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A statement line as returned by
 * {@code GET /api/v1/transactions/accounts/{accountNumber}}.
 *
 * @param transactionRef unique reference
 * @param accountNumber  the owning account
 * @param direction      CREDIT or DEBIT
 * @param amount         the (unsigned) amount
 * @param currency       ISO currency
 * @param description    free-text description (treated as untrusted data)
 * @param counterparty   the other side of the transaction, if any
 * @param bookedAt       when the entry was booked
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Transaction(
        String transactionRef,
        String accountNumber,
        String direction,
        BigDecimal amount,
        String currency,
        String description,
        String counterparty,
        Instant bookedAt) {

    /**
     * @return the signed amount: positive for CREDIT, negative for DEBIT. Used by
     *         categorisation aggregation so totals reflect money in vs out.
     */
    public BigDecimal signedAmount() {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        return "DEBIT".equalsIgnoreCase(direction) ? amount.negate() : amount;
    }
}
