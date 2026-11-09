package sg.edu.rp.sandbox.ai.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/* TokenUsage lives in the same package (sg.edu.rp.sandbox.ai.llm). */

/**
 * Test fixture {@link LlmClient}: returns a programmed sequence of responses (any
 * mix of tool-call turns and a final text turn) with no network. Records every
 * {@link LlmRequest} it received so tests can assert message assembly/ordering.
 *
 * <p>If the script is exhausted, it returns a default final text turn so loops
 * terminate deterministically.
 */
public class StubLlmClient implements LlmClient {

    private final Deque<LlmResponse> scripted = new ArrayDeque<>();
    private final List<LlmRequest> received = new ArrayList<>();
    private RuntimeException failure;
    private String exhaustedReply = "Done.";
    private TokenUsage usage = TokenUsage.zero();

    /** Program the next response(s), in order. */
    public StubLlmClient thenReturn(LlmResponse response) {
        scripted.addLast(response);
        return this;
    }

    /**
     * Configure the {@link TokenUsage} attached to responses produced by the
     * {@code thenToolCall}/{@code thenToolCalls}/{@code thenText} helpers and to
     * the exhausted-script fallback. Defaults to {@link TokenUsage#zero()}. Lets
     * orchestrator tests assert summed per-turn totals without any network.
     */
    public StubLlmClient withUsage(TokenUsage usage) {
        this.usage = usage == null ? TokenUsage.zero() : usage;
        return this;
    }

    /** Program a tool-call turn requesting the named tool with the given JSON args. */
    public StubLlmClient thenToolCall(String callId, String name, String argumentsJson) {
        scripted.addLast(LlmResponse.toolCalls(List.of(new LlmToolCall(callId, name, argumentsJson)), usage));
        return this;
    }

    /** Program multiple parallel tool calls in a single turn. */
    public StubLlmClient thenToolCalls(List<LlmToolCall> calls) {
        scripted.addLast(LlmResponse.toolCalls(calls, usage));
        return this;
    }

    /** Program a final assistant text turn. */
    public StubLlmClient thenText(String content) {
        scripted.addLast(LlmResponse.text(content, usage));
        return this;
    }

    /** Make the next (and all subsequent) calls throw the given failure. */
    public StubLlmClient failWith(RuntimeException ex) {
        this.failure = ex;
        return this;
    }

    public StubLlmClient exhaustedReply(String reply) {
        this.exhaustedReply = reply;
        return this;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        received.add(request);
        if (failure != null) {
            throw failure;
        }
        if (scripted.isEmpty()) {
            return LlmResponse.text(exhaustedReply, usage);
        }
        return scripted.removeFirst();
    }

    /** All requests seen, in call order. */
    public List<LlmRequest> received() {
        return List.copyOf(received);
    }

    public LlmRequest lastRequest() {
        return received.isEmpty() ? null : received.get(received.size() - 1);
    }

    public LlmRequest firstRequest() {
        return received.isEmpty() ? null : received.get(0);
    }

    public int callCount() {
        return received.size();
    }
}
