package sg.edu.rp.sandbox.ai.dto;

/**
 * A client's decision on a previously proposed transfer.
 *
 * <p>Present only when the chat request is a confirmation. In Phase 1 the service
 * does not execute transfers; a confirmation yields a clear "not supported yet"
 * message.
 *
 * @param confirmationToken the backend-issued token identifying the proposed intent
 * @param confirmed         whether the user confirmed execution
 */
public record ConfirmationDecision(String confirmationToken, boolean confirmed) {
}
