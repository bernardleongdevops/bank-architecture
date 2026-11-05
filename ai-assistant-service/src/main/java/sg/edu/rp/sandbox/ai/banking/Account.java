package sg.edu.rp.sandbox.ai.banking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * An account as returned by {@code GET /api/v1/accounts} and
 * {@code GET /api/v1/accounts/{accountNumber}}.
 *
 * @param accountNumber e.g. ACC-1001
 * @param accountType   e.g. SAVINGS, CURRENT
 * @param currency      ISO currency, e.g. SGD
 * @param balance       current balance
 * @param status        e.g. ACTIVE
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Account(
        String accountNumber,
        String accountType,
        String currency,
        BigDecimal balance,
        String status) {
}
