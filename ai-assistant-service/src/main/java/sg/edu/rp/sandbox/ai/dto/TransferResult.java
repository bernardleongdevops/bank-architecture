package sg.edu.rp.sandbox.ai.dto;

/**
 * The outcome of an executed transfer. Part of the shared response shape; Phase 1
 * never populates this (transfers are Phase 2).
 *
 * @param status  the payment-service HTTP status (201 | 202 | 422 | 409 | 403 ...)
 * @param outcome canonical outcome (COMPLETED | AML_HELD | AML_BLOCKED | CONFLICT | FORBIDDEN)
 * @param message plain-language explanation for the user
 */
public record TransferResult(
        int status,
        String outcome,
        String message) {
}
