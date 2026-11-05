package sg.edu.rp.sandbox.ai.llm;

import java.util.List;

/**
 * A provider-neutral chat message in the conversation sent to the model.
 *
 * <p>This is deliberately NOT the OpenAI wire shape - all OpenAI-specific JSON
 * mapping lives inside {@code OpenAiLlmClient}. A message may be:
 * <ul>
 *   <li>a {@code system}/{@code user}/{@code assistant} text message, or</li>
 *   <li>an {@code assistant} message carrying {@code toolCalls} (the model asking
 *       to run tools), or</li>
 *   <li>a {@code tool} result message carrying the output of a previously
 *       requested tool call (keyed by {@code toolCallId}).</li>
 * </ul>
 *
 * @param role       one of "system", "user", "assistant", "tool"
 * @param content    text content (may be null for a pure tool-call assistant turn)
 * @param toolCalls  tool calls requested by the assistant (empty unless role=assistant)
 * @param toolCallId for a tool-result message, the id of the call it answers (else null)
 */
public record LlmMessage(
        String role,
        String content,
        List<LlmToolCall> toolCalls,
        String toolCallId) {

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content, List.of(), null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, List.of(), null);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content, List.of(), null);
    }

    public static LlmMessage assistantToolCalls(List<LlmToolCall> toolCalls) {
        return new LlmMessage("assistant", null, toolCalls, null);
    }

    /** A tool-result message answering a prior tool call. */
    public static LlmMessage toolResult(String toolCallId, String content) {
        return new LlmMessage("tool", content, List.of(), toolCallId);
    }

    public List<LlmToolCall> toolCallsOrEmpty() {
        return toolCalls == null ? List.of() : toolCalls;
    }
}
