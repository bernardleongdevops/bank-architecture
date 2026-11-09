package sg.edu.rp.sandbox.ai.llm;

/**
 * Immutable OpenAI token-usage snapshot for a single chat-completion call.
 *
 * <p>Additive observability type (Guardrail A). Surfaced through the
 * {@link LlmClient} seam on {@link LlmResponse} so the orchestrator can sum
 * per-turn totals for structured logging. Carries only numeric counts - no
 * secrets, message content, or tool bodies.
 *
 * @param promptTokens     tokens consumed by the prompt (input)
 * @param completionTokens tokens produced in the completion (output)
 * @param totalTokens      total tokens for the call (usually prompt + completion)
 */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {

    private static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    /** A zero usage, used when a provider omits the {@code usage} object. */
    public static TokenUsage zero() {
        return ZERO;
    }

    public static TokenUsage of(int promptTokens, int completionTokens, int totalTokens) {
        return new TokenUsage(promptTokens, completionTokens, totalTokens);
    }

    /** @return a new usage equal to this one plus {@code other}, field by field. */
    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(
                this.promptTokens + other.promptTokens,
                this.completionTokens + other.completionTokens,
                this.totalTokens + other.totalTokens);
    }
}
