package sg.edu.rp.sandbox.ai.llm;

import java.util.List;

/**
 * A provider-neutral chat-completion response. Exactly one of two shapes:
 * <ul>
 *   <li>a final assistant <em>text</em> answer ({@link #toolCalls} empty), or</li>
 *   <li>one or more {@link #toolCalls} the model wants executed (text usually null).</li>
 * </ul>
 *
 * <p>Additionally carries the {@link TokenUsage} reported by the provider for this
 * call (Guardrail A observability). This field is <strong>additive</strong>: the
 * existing {@link #text(String)}/{@link #toolCalls(List)} factories and the
 * two-argument constructor keep working and default usage to {@link TokenUsage#zero()},
 * so no existing call site breaks.
 *
 * @param content   assistant text (null/blank when the model requested tools)
 * @param toolCalls tool calls requested by the model (empty for a final text answer)
 * @param usage     token usage for this call (never null; zero when the provider omits it)
 */
public record LlmResponse(String content, List<LlmToolCall> toolCalls, TokenUsage usage) {

    /** Back-compat constructor: defaults usage to zero so existing two-arg uses keep compiling. */
    public LlmResponse(String content, List<LlmToolCall> toolCalls) {
        this(content, toolCalls, TokenUsage.zero());
    }

    public LlmResponse {
        if (usage == null) {
            usage = TokenUsage.zero();
        }
    }

    public static LlmResponse text(String content) {
        return new LlmResponse(content, List.of(), TokenUsage.zero());
    }

    public static LlmResponse text(String content, TokenUsage usage) {
        return new LlmResponse(content, List.of(), usage);
    }

    public static LlmResponse toolCalls(List<LlmToolCall> toolCalls) {
        return new LlmResponse(null, toolCalls, TokenUsage.zero());
    }

    public static LlmResponse toolCalls(List<LlmToolCall> toolCalls, TokenUsage usage) {
        return new LlmResponse(null, toolCalls, usage);
    }

    public List<LlmToolCall> toolCallsOrEmpty() {
        return toolCalls == null ? List.of() : toolCalls;
    }

    /** @return the token usage for this call, never null. */
    public TokenUsage usageOrZero() {
        return usage == null ? TokenUsage.zero() : usage;
    }

    /** @return true when the model requested at least one tool call. */
    public boolean hasToolCalls() {
        return !toolCallsOrEmpty().isEmpty();
    }
}
