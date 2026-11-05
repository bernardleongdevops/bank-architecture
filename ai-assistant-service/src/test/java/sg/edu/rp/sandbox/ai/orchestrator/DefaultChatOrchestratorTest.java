package sg.edu.rp.sandbox.ai.orchestrator;

import org.junit.jupiter.api.Test;
import sg.edu.rp.sandbox.ai.banking.AccessToken;
import sg.edu.rp.sandbox.ai.banking.StubBankingApiClient;
import sg.edu.rp.sandbox.ai.dto.ChatRequest;
import sg.edu.rp.sandbox.ai.dto.ChatResponse;
import sg.edu.rp.sandbox.ai.dto.ConfirmationDecision;
import sg.edu.rp.sandbox.ai.llm.LlmException;
import sg.edu.rp.sandbox.ai.llm.LlmResponse;
import sg.edu.rp.sandbox.ai.llm.LlmToolCall;
import sg.edu.rp.sandbox.ai.llm.StubLlmClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 6.6 - unit tests for loop termination and LLM-failure handling, plus the
 * Phase 1 confirmation behaviour.
 */
class DefaultChatOrchestratorTest {

    private final AccessToken token = AccessToken.of("user-token");

    @Test
    void finalTextIsReturnedAsReply() {
        StubLlmClient llm = new StubLlmClient().thenText("Your savings account ACC-1001 has SGD 5,000.00.");
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(llm, new StubBankingApiClient(), 5);

        ChatResponse response = orchestrator.handle(
                new ChatRequest("how much in savings?", List.of(), null), token);

        assertThat(response.type()).isEqualTo("message");
        assertThat(response.reply()).contains("ACC-1001");
        assertThat(response.toolsUsed()).isEmpty();
    }

    @Test
    void toolCallThenFinalTextPopulatesToolsUsed() {
        StubLlmClient llm = new StubLlmClient()
                .thenToolCall("c1", "getAccounts", "{}")
                .thenText("You have two accounts.");
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(llm, new StubBankingApiClient(), 5);

        ChatResponse response = orchestrator.handle(
                new ChatRequest("list my accounts", List.of(), null), token);

        assertThat(response.type()).isEqualTo("message");
        assertThat(response.reply()).isEqualTo("You have two accounts.");
        assertThat(response.toolsUsed()).containsExactly("getAccounts");
    }

    @Test
    void llmFailureYieldsTemporarilyUnavailableError() {
        StubLlmClient llm = new StubLlmClient().failWith(new LlmException("boom"));
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(llm, new StubBankingApiClient(), 5);

        ChatResponse response = orchestrator.handle(
                new ChatRequest("hello", List.of(), null), token);

        assertThat(response.type()).isEqualTo("error");
        assertThat(response.reply()).isEqualTo(DefaultChatOrchestrator.UNAVAILABLE_MESSAGE);
    }

    @Test
    void loopBoundReachedReturnsGracefulMessage() {
        // Model always asks for a tool; bound is 2.
        StubLlmClient llm = new StubLlmClient()
                .thenToolCall("c1", "getAccounts", "{}")
                .thenToolCall("c2", "getAccounts", "{}")
                .thenToolCall("c3", "getAccounts", "{}");
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(llm, new StubBankingApiClient(), 2);

        ChatResponse response = orchestrator.handle(
                new ChatRequest("loop", List.of(), null), token);

        assertThat(response.type()).isEqualTo("message");
        assertThat(response.reply()).isEqualTo(DefaultChatOrchestrator.LOOP_EXHAUSTED_MESSAGE);
        assertThat(llm.callCount()).isEqualTo(2);
    }

    @Test
    void confirmationOnlyRequestIsDeclinedInPhase1() {
        StubLlmClient llm = new StubLlmClient().thenText("should not be called");
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(llm, new StubBankingApiClient(), 5);

        ChatResponse response = orchestrator.handle(
                new ChatRequest(null, List.of(), new ConfirmationDecision("tok-123", true)), token);

        assertThat(response.type()).isEqualTo("message");
        assertThat(response.reply()).isEqualTo(DefaultChatOrchestrator.TRANSFERS_NOT_SUPPORTED_MESSAGE);
        // The model is not even consulted for a Phase 1 confirmation.
        assertThat(llm.callCount()).isZero();
    }

    @Test
    void parallelToolCallsAllRecordedInToolsUsed() {
        StubLlmClient llm = new StubLlmClient()
                .thenToolCalls(List.of(
                        new LlmToolCall("c1", "getAccount", "{\"accountNumber\":\"ACC-1001\"}"),
                        new LlmToolCall("c2", "getAccount", "{\"accountNumber\":\"ACC-1002\"}")))
                .thenReturn(LlmResponse.text("Here are both accounts."));
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(llm, new StubBankingApiClient(), 5);

        ChatResponse response = orchestrator.handle(
                new ChatRequest("show both", List.of(), null), token);

        assertThat(response.toolsUsed()).containsExactly("getAccount", "getAccount");
        assertThat(response.reply()).isEqualTo("Here are both accounts.");
    }
}
