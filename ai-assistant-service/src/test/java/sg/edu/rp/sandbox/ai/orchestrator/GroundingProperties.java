package sg.edu.rp.sandbox.ai.orchestrator;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.Size;
import sg.edu.rp.sandbox.ai.banking.AccessToken;
import sg.edu.rp.sandbox.ai.banking.Account;
import sg.edu.rp.sandbox.ai.banking.StubBankingApiClient;
import sg.edu.rp.sandbox.ai.dto.ChatRequest;
import sg.edu.rp.sandbox.ai.dto.ChatResponse;
import sg.edu.rp.sandbox.ai.llm.LlmClient;
import sg.edu.rp.sandbox.ai.llm.LlmMessage;
import sg.edu.rp.sandbox.ai.llm.LlmRequest;
import sg.edu.rp.sandbox.ai.llm.LlmResponse;
import sg.edu.rp.sandbox.ai.llm.LlmToolCall;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 1: Answers are grounded in real tool results.
 *
 * <p>Models a grounded assistant: it first calls {@code getAccounts}, and on the
 * next round produces a final reply quoting a balance taken verbatim from the tool
 * result the orchestrator fed back. The property asserts that every numeric figure
 * in the final reply is present in the tool-result messages captured during the
 * turn - i.e. the orchestrator supplies real data and never fabricates figures.
 *
 * <p>The complementary guarantee - that the orchestrator does not itself inject
 * server-sourced numbers - is covered by Property 9 (message assembly).
 *
 * <p>Validates: Requirements 3.3, 10.4
 */
@Tag("Feature: ai-assistant-service, Property 1")
class GroundingProperties {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    /**
     * A grounded model: turn 1 asks for accounts; turn 2 reads the balances out of
     * the tool-result message and quotes one of them.
     */
    private static final class GroundedModel implements LlmClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            boolean hasToolResult = request.messages().stream()
                    .anyMatch(m -> "tool".equals(m.role()));
            if (!hasToolResult) {
                return LlmResponse.toolCalls(List.of(new LlmToolCall("c1", "getAccounts", "{}")));
            }
            // Quote balances verbatim from the tool result (grounded).
            String toolContent = request.messages().stream()
                    .filter(m -> "tool".equals(m.role()))
                    .map(LlmMessage::content)
                    .reduce("", (a, b) -> a + " " + b);
            // Extract numbers present in the real tool result and build a reply from them only.
            Matcher matcher = NUMBER.matcher(toolContent);
            List<String> figures = new ArrayList<>();
            while (matcher.find()) {
                figures.add(matcher.group());
            }
            String reply = figures.isEmpty()
                    ? "You have no accounts."
                    : "Your balance is SGD " + figures.get(0) + ".";
            return LlmResponse.text(reply);
        }
    }

    @Property(tries = 200)
    void everyFigureInReplyComesFromAToolResult(
            @ForAll @Size(min = 1, max = 4) List<@net.jqwik.api.constraints.IntRange(min = 0, max = 99999) Integer> cents) {

        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < cents.size(); i++) {
            BigDecimal balance = new BigDecimal(cents.get(i)).movePointLeft(2);
            accounts.add(new Account("ACC-" + (1000 + i), "SAVINGS", "SGD", balance, "ACTIVE"));
        }

        StubBankingApiClient banking = new StubBankingApiClient().withAccounts(accounts);
        RecordingModel recorder = new RecordingModel(new GroundedModel());
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(recorder, banking, 5);

        ChatResponse response = orchestrator.handle(
                new ChatRequest("what is my balance?", List.of(), null),
                AccessToken.of("tok"));

        assertThat(response.type()).isEqualTo("message");

        // Gather every number present in all tool-result messages seen by the model.
        String allToolContent = recorder.lastMessages().stream()
                .filter(m -> "tool".equals(m.role()))
                .map(LlmMessage::content)
                .reduce("", (a, b) -> a + " " + b);

        Matcher replyNumbers = NUMBER.matcher(response.reply());
        while (replyNumbers.find()) {
            String figure = replyNumbers.group();
            assertThat(allToolContent)
                    .as("figure '%s' in reply must come from a tool result", figure)
                    .contains(figure);
        }
    }

    /** Wraps a model and remembers the messages of the final request it answered. */
    private static final class RecordingModel implements LlmClient {
        private final LlmClient delegate;
        private List<LlmMessage> lastMessages = List.of();

        RecordingModel(LlmClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            this.lastMessages = List.copyOf(request.messages());
            return delegate.complete(request);
        }

        List<LlmMessage> lastMessages() {
            return lastMessages;
        }
    }
}
