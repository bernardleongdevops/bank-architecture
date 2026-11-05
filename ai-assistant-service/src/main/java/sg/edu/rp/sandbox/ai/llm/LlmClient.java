package sg.edu.rp.sandbox.ai.llm;

/**
 * Seam for the model provider. The <strong>only</strong> component that talks to
 * OpenAI. An interface so tests can script tool-call sequences and final answers
 * with no network.
 */
public interface LlmClient {

    /**
     * Run one chat-completion round-trip.
     *
     * @param request the model, messages, and tool definitions
     * @return either a final assistant text answer or a set of tool calls
     * @throws LlmException on timeout, transport error, or an upstream error status
     */
    LlmResponse complete(LlmRequest request);
}
