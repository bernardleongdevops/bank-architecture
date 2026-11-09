package sg.edu.rp.sandbox.ai.orchestrator;

import sg.edu.rp.sandbox.ai.banking.AccessToken;
import sg.edu.rp.sandbox.ai.dto.ChatRequest;
import sg.edu.rp.sandbox.ai.dto.ChatResponse;

/**
 * Runs the multi-turn tool loop: assembles messages (system prompt + history +
 * new user turn), calls the model, dispatches any requested tools, and re-invokes
 * until a final text answer or the configured iteration bound is reached.
 */
public interface ChatOrchestrator {

    /**
     * Handle one chat turn on-behalf-of the given user.
     *
     * @param request    the chat request (message and/or confirmation + history)
     * @param onBehalfOf the signed-in user's access token, reused for gateway calls
     * @param subject    the authenticated user's subject (JWT {@code sub} claim),
     *                   used to bind/verify the Phase 2 confirmation token so a
     *                   transfer can only be confirmed by the user who proposed it
     * @return exactly one {@link ChatResponse} ("message", "confirm_transfer", or "error")
     */
    ChatResponse handle(ChatRequest request, AccessToken onBehalfOf, String subject);

    /**
     * Convenience overload with no subject. Used where the caller has no JWT subject
     * (e.g. some tests); a null subject means no confirmation token can verify, so
     * no transfer can execute.
     */
    default ChatResponse handle(ChatRequest request, AccessToken onBehalfOf) {
        return handle(request, onBehalfOf, null);
    }
}
