package sg.edu.rp.sandbox.ai.orchestrator;

import sg.edu.rp.sandbox.ai.llm.TokenUsage;

import java.util.List;

/**
 * Additive per-turn observability record (Guardrail A). One of these is built and
 * logged at INFO at the end of every chat turn.
 *
 * <p><strong>Redaction (Requirement 11.3 / Property 10):</strong> this record
 * deliberately carries only tool <em>names</em>, counts, an outcome label, and
 * numeric token usage. It MUST NOT contain the OpenAI key, the on-behalf-of bearer
 * token, any chat message content, or any tool result body. Its {@link #toString()}
 * is what gets logged, so it is kept free of any of those values by construction.
 *
 * @param toolsDispatched tool NAMES only, in dispatch order (e.g. ["getAccounts","getTransactions"])
 * @param iterations      Tool_Iterations used this turn (model round-trips performed)
 * @param outcome         the turn outcome
 * @param usage           token usage summed across the model calls made this turn
 */
public record TurnLog(
        List<String> toolsDispatched,
        int iterations,
        Outcome outcome,
        TokenUsage usage) {

    public TurnLog {
        toolsDispatched = toolsDispatched == null ? List.of() : List.copyOf(toolsDispatched);
        if (usage == null) {
            usage = TokenUsage.zero();
        }
    }

    /** The exactly-one-of turn outcome (Requirement 15.1). */
    public enum Outcome {
        /** A final assistant text answer was produced. */
        ANSWERED,
        /** The {@code ai.max-tool-iterations} bound was reached without a final answer. */
        LOOP_EXHAUSTED,
        /** An {@link sg.edu.rp.sandbox.ai.llm.LlmException} was raised during the turn. */
        LLM_ERROR,
        /** A tool dispatch error terminated the turn. */
        TOOL_ERROR
    }

    /**
     * Compact, log-safe rendering. Contains only names, counts, outcome, and token
     * counts - no secrets, message content, or tool bodies.
     */
    @Override
    public String toString() {
        return "turn{"
                + "toolsDispatched=" + toolsDispatched
                + ", toolCount=" + toolsDispatched.size()
                + ", iterations=" + iterations
                + ", outcome=" + outcome
                + ", usage={prompt=" + usage.promptTokens()
                + ", completion=" + usage.completionTokens()
                + ", total=" + usage.totalTokens() + "}"
                + "}";
    }
}
