package sg.edu.rp.sandbox.ai.orchestrator;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import sg.edu.rp.sandbox.ai.banking.AccessToken;
import sg.edu.rp.sandbox.ai.banking.StubBankingApiClient;
import sg.edu.rp.sandbox.ai.dto.ChatRequest;
import sg.edu.rp.sandbox.ai.llm.LlmException;
import sg.edu.rp.sandbox.ai.llm.LlmResponse;
import sg.edu.rp.sandbox.ai.llm.LlmToolCall;
import sg.edu.rp.sandbox.ai.llm.StubLlmClient;
import sg.edu.rp.sandbox.ai.llm.TokenUsage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 15.4(b)/(c) - the orchestrator emits exactly one structured per-turn record
 * carrying tool names + iterations + outcome + summed token usage, and that record
 * leaks no secrets, no on-behalf-of bearer token, and no message/tool-result bodies
 * (Requirement 15.1-15.3, 11.3 / Property 10). Also verifies that StubLlmClient
 * usage flows through and sums across model calls (15.5).
 */
class TurnLoggingTest {

    private static final String BEARER = "super-secret-user-bearer-token-xyz";
    private static final String OPENAI_KEY = "sk-topsecret-openai-key-abc123";

    private final AccessToken token = AccessToken.of(BEARER);

    private Logger orchestratorLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        orchestratorLogger = (Logger) LoggerFactory.getLogger(DefaultChatOrchestrator.class);
        appender = new ListAppender<>();
        appender.start();
        orchestratorLogger.addAppender(appender);
        orchestratorLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachAppender() {
        orchestratorLogger.detachAppender(appender);
    }

    private String turnLine() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("chat turn complete"))
                .reduce((first, second) -> second) // last turn record
                .orElseThrow(() -> new AssertionError("no per-turn record was logged"));
    }

    @Test
    void emitsTurnRecordWithToolsIterationsOutcomeAndSummedUsage() {
        // Two model calls: a tool-call turn (usage 10) then a final text turn (usage 7) -> total 17.
        StubLlmClient llm = new StubLlmClient()
                .thenReturn(LlmResponse.toolCalls(
                        List.of(new LlmToolCall("c1", "getAccounts", "{}")),
                        new TokenUsage(6, 4, 10)))
                .thenReturn(LlmResponse.text(
                        "You have two accounts.",
                        new TokenUsage(3, 4, 7)));
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(llm, new StubBankingApiClient(), 5);

        orchestrator.handle(new ChatRequest("list my accounts", List.of(), null), token);

        String line = turnLine();
        // tool names only, outcome ANSWERED, iterations = 2 model round-trips.
        assertThat(line).contains("toolsDispatched=[getAccounts]");
        assertThat(line).contains("outcome=ANSWERED");
        assertThat(line).contains("iterations=2");
        // summed usage: prompt 6+3=9, completion 4+4=8, total 10+7=17.
        assertThat(line).contains("prompt=9");
        assertThat(line).contains("completion=8");
        assertThat(line).contains("total=17");
    }

    @Test
    void turnRecordContainsNoSecretsOrMessageBodies() {
        StubLlmClient llm = new StubLlmClient()
                .withUsage(new TokenUsage(5, 5, 10))
                .thenToolCall("c1", "getAccounts", "{}")
                .thenText("Your savings account ACC-1001 has SGD 5,000.00.");
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(llm, new StubBankingApiClient(), 5);

        orchestrator.handle(
                new ChatRequest("what is my secret balance ACC-1001?", List.of(), null), token);

        String line = turnLine();
        // No bearer token, no OpenAI key, no user message content, no tool-result body.
        assertThat(line).doesNotContain(BEARER);
        assertThat(line).doesNotContain(OPENAI_KEY);
        assertThat(line).doesNotContain("secret balance");
        assertThat(line).doesNotContain("ACC-1001");
        assertThat(line).doesNotContain("SGD 5,000.00");
        // Still carries the tool NAME and counts.
        assertThat(line).contains("getAccounts");
        assertThat(line).contains("toolCount=1");
    }

    @Test
    void loopExhaustedOutcomeIsRecorded() {
        StubLlmClient llm = new StubLlmClient()
                .withUsage(new TokenUsage(1, 1, 2))
                .thenToolCall("c1", "getAccounts", "{}")
                .thenToolCall("c2", "getAccounts", "{}")
                .thenToolCall("c3", "getAccounts", "{}");
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(llm, new StubBankingApiClient(), 2);

        orchestrator.handle(new ChatRequest("loop", List.of(), null), token);

        String line = turnLine();
        assertThat(line).contains("outcome=LOOP_EXHAUSTED");
        assertThat(line).contains("iterations=2");
        // two model calls each usage 2 -> total 4.
        assertThat(line).contains("total=4");
    }

    @Test
    void llmErrorOutcomeIsRecorded() {
        StubLlmClient llm = new StubLlmClient().failWith(new LlmException("boom"));
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(llm, new StubBankingApiClient(), 5);

        orchestrator.handle(new ChatRequest("hello", List.of(), null), token);

        assertThat(turnLine()).contains("outcome=LLM_ERROR");
    }

    @Test
    void buildTurnLogSumsUsageAndKeepsToolNamesOnly() {
        // Directly exercise the pure, testable record-builder (no log parsing).
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(new StubLlmClient(), new StubBankingApiClient(), 5);

        TurnLog turnLog = orchestrator.buildTurnLog(
                List.of("getAccounts", "getTransactions"),
                3,
                TurnLog.Outcome.ANSWERED,
                new TokenUsage(10, 7, 17));

        assertThat(turnLog.toolsDispatched()).containsExactly("getAccounts", "getTransactions");
        assertThat(turnLog.iterations()).isEqualTo(3);
        assertThat(turnLog.outcome()).isEqualTo(TurnLog.Outcome.ANSWERED);
        assertThat(turnLog.usage()).isEqualTo(new TokenUsage(10, 7, 17));
        assertThat(turnLog.toString()).doesNotContain(BEARER).doesNotContain(OPENAI_KEY);
    }
}
