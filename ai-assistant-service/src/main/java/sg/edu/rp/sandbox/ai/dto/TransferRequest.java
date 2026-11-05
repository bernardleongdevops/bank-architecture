package sg.edu.rp.sandbox.ai.dto;

import java.math.BigDecimal;

/**
 * Mirrors the {@code POST /api/v1/payments/transfers} request body exactly. Part of
 * the shared contract; Phase 1 never sends one (transfers are Phase 2).
 *
 * @param fromAccount source account
 * @param toAccount   destination account
 * @param amount      positive amount
 * @param description short free-text note
 */
public record TransferRequest(
        String fromAccount,
        String toAccount,
        BigDecimal amount,
        String description) {
}
