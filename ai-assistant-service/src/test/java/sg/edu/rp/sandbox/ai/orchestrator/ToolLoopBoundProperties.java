package sg.edu.rp.sandbox.ai.orchestrator;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import sg.edu.rp.sandbox.ai.banking.AccessToken;
import sg.edu.rp.sandbox.ai.banking.StubBankingApiClient;
import sg.edu.rp.sandbox.ai.dto.ChatRequest;
import sg.edu.rp.sandbox.ai.dto.ChatResponse;
import sg.edu.rp.sandbox.ai.llm.LlmClient;
import sg.edu.rp.sandbox.ai.llm.LlmRequest;
import sg.edu.rp.sandbox.ai.llm.LlmResponse;
import sg.edu.rp.sandbox.ai.llm.LlmToolCall;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 7: Tool loop terminates within the configured bound.
 *
 * <p>Even when the model <em>always</em> requests another tool call (never
 * returning a final answer), the orchestrator performs at most
 * {@code ai.max-tool-iterations} model round-trips and always returns a response.
 *
 * <p>Validates: Requirements 3.4, 3.5
 */
@Tag("Feature: ai-assistant-service, Property 7")
class ToolLoopBoundProperties {

    /** A model that never stops asking for tools. */
    private static final class AlwaysToolCalling implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmResponse complete(LlmRequest request) {
            int n = calls.incrementAndGet();
            return LlmResponse.toolCalls(List.of(new LlmToolCall("call-" + n, "getAccounts", "{}")));
        }
    }

    @Property(tries = 200)
    void loopNeverExceedsConfiguredBound(@ForAll @IntRange(min = 1, max = 12) int maxIterations) {
        AlwaysToolCalling llm = new AlwaysToolCalling();
        StubBankingApiClient banking = new StubBankingApiClient();
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(llm, banking, maxIterations);

        ChatResponse response = orchestrator.handle(
                new ChatRequest("loop forever please", List.of(), null),
                AccessToken.of("tok"));

        // A response is always returned (never hangs).
        assertThat(response).isNotNull();
        assertThat(response.type()).isIn("message", "error");
        // The model was called at most maxIterations times.
        assertThat(llm.calls.get()).isLessThanOrEqualTo(maxIterations);
        // And exactly maxIterations in this always-tool-calling scenario.
        assertThat(llm.calls.get()).isEqualTo(maxIterations);
    }
}
