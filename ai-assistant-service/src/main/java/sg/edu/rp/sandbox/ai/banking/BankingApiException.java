package sg.edu.rp.sandbox.ai.banking;

import sg.edu.rp.sandbox.common.error.ApiError;

/**
 * Thrown when a gateway call returns an error status (401/403/404/409/422/429/5xx)
 * or the call fails at the transport level.
 *
 * <p>Preserves the HTTP {@code status} and, where available, the standard gateway
 * error body {@code { timestamp, status, error, message, path }} so the orchestrator
 * can surface {@code error}/{@code message} in plain language without altering the
 * status meaning. The on-behalf-of token is never included here.
 */
public class BankingApiException extends RuntimeException {

    private final int status;
    private final String errorCode;
    private final ApiError body;

    public BankingApiException(int status, String errorCode, String message, ApiError body) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.body = body;
    }

    public int status() {
        return status;
    }

    /** Short error code from the gateway body (e.g. INSUFFICIENT_FUNDS), or null. */
    public String errorCode() {
        return errorCode;
    }

    /** The full standard error body, or null if none was parseable. */
    public ApiError body() {
        return body;
    }
}
