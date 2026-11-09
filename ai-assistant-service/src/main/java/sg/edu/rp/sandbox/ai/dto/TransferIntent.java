package sg.edu.rp.sandbox.ai.dto;

import java.math.BigDecimal;

/**
 * A structured proposed transfer. Part of the shared response shape; Phase 1 never
 * populates this (it belongs to the Phase 2 confirmation handshake).
 *
 * @param fromAccount source account the user owns
 * @param toAccount   destination account
 * @param amount      positive amount in the account currency
 * @param currency    echoed from the resolved source account
 * @param description short free-text note
 */
public record TransferIntent(
        String fromAccount,
        String toAccount,
        BigDecimal amount,
        String currency,
        String description) {
}
