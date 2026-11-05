package sg.edu.rp.sandbox.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response body for {@code POST /ai/chat}.
 *
 * <p>In Phase 1 {@code type} is either {@code "message"} or {@code "error"}. The
 * {@code confirm_transfer} shape, {@code intent}, {@code confirmationToken} and
 * {@code transferResult} fields exist for the shared contract but are never
 * populated in Phase 1 (they belong to Phase 2).
 *
 * @param type             "message" | "confirm_transfer" | "error"
 * @param reply            natural-language text for type=message/error
 * @param intent           present when type=confirm_transfer (Phase 2)
 * @param confirmationToken present when type=confirm_transfer (Phase 2)
 * @param humanSummary     present when type=confirm_transfer (Phase 2)
 * @param transferResult   present after a confirmed transfer executes (Phase 2)
 * @param toolsUsed        names of tools invoked this turn (observability)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatResponse(
        String type,
        String reply,
        TransferIntent intent,
        String confirmationToken,
        String humanSummary,
        TransferResult transferResult,
        List<String> toolsUsed) {

    /** A normal assistant text turn. */
    public static ChatResponse message(String reply, List<String> toolsUsed) {
        return new ChatResponse("message", reply, null, null, null, null, toolsUsed);
    }

    /** An error turn (temporarily unavailable, throttled, session expired, ...). */
    public static ChatResponse error(String reply) {
        return new ChatResponse("error", reply, null, null, null, null, List.of());
    }
}
