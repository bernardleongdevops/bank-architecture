package sg.edu.rp.sandbox.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;

import java.util.List;

/**
 * Request body for {@code POST /ai/chat}.
 *
 * <p>Validation rule (Requirement 1.4): a request must carry either a non-blank
 * {@code message} or a {@code confirmation} decision. An empty body (neither
 * present) fails validation and is rejected with HTTP 400.
 *
 * @param message      the new user turn; null/blank when this call is only a confirmation
 * @param history      prior turns the client replays (stateless conversation); may be null
 * @param confirmation non-null only when confirming a previously proposed transfer
 */
public record ChatRequest(
        String message,
        List<ChatMessage> history,
        ConfirmationDecision confirmation) {

    /** @return the history list, never null. */
    public List<ChatMessage> historyOrEmpty() {
        return history == null ? List.of() : history;
    }

    /** @return true when {@code message} has non-whitespace content. */
    @JsonIgnore
    public boolean hasMessage() {
        return message != null && !message.isBlank();
    }

    /**
     * Bean-validation constraint: at least one of a non-blank message or a
     * confirmation decision must be present.
     */
    @AssertTrue(message = "Request must contain a non-blank message or a confirmation decision")
    @JsonIgnore
    public boolean isMessageOrConfirmationPresent() {
        return hasMessage() || confirmation != null;
    }
}
