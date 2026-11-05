package sg.edu.rp.sandbox.ai.orchestrator;

import org.junit.jupiter.api.Test;
import sg.edu.rp.sandbox.ai.banking.AccessToken;
import sg.edu.rp.sandbox.ai.banking.Account;
import sg.edu.rp.sandbox.ai.banking.StubBankingApiClient;
import sg.edu.rp.sandbox.ai.dto.ChatRequest;
import sg.edu.rp.sandbox.ai.dto.ChatResponse;
import sg.edu.rp.sandbox.ai.llm.LlmToolCall;
import sg.edu.rp.sandbox.ai.llm.StubLlmClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 14.2 - deterministic orchestrator/tool-loop tests for the Phase 1 breadth
 * behaviours added in Requirement 14. These assert BEHAVIOUR (which tools were
 * dispatched, {@code toolsUsed} contents, the iteration bound, and the response
 * {@code type}) using the scriptable {@link StubLlmClient} and the recording
 * {@link StubBankingApiClient}. They never assert exact model wording, which is
 * non-deterministic and validated live.
 */
class OrchestratorBreadthTest {

    private final AccessToken token = AccessToken.of("user-token");

    /**
     * Multi-account aggregation path (Requirement 14.1): the model first asks for
     * getAccounts, then issues one getTransactions call per returned account, then
     * produces a final answer. The orchestrator must dispatch every call, feed each
     * result back, stay within the bound, and reflect the tools in toolsUsed / type.
     */
    @Test
    void multiAccountAggregationDispatchesGetTransactionsPerAccount() {
        List<Account> accounts = List.of(
                new Account("ACC-1001", "SAVINGS", "SGD", new BigDecimal("5000.00"), "ACTIVE"),
                new Account("ACC-1002", "CURRENT", "SGD", new BigDecimal("1200.50"), "ACTIVE"),
                new Account("ACC-1003", "SAVINGS", "SGD", new BigDecimal("300.00"), "ACTIVE"));
        StubBankingApiClient banking = new StubBankingApiClient().withAccounts(accounts);

        // Iteration 1: list accounts. Iteration 2: one getTransactions per account.
        // Iteration 3: final grounded answer.
        StubLlmClient llm = new StubLlmClient()
                .thenToolCall("c0", "getAccounts", "{}")
                .thenToolCalls(List.of(
                        new LlmToolCall("c1", "getTransactions", "{\"accountNumber\":\"ACC-1001\"}"),
                        new LlmToolCall("c2", "getTransactions", "{\"accountNumber\":\"ACC-1002\"}"),
                        new LlmToolCall("c3", "getTransactions", "{\"accountNumber\":\"ACC-1003\"}")))
                .thenText("Across your three accounts you spent SGD 34.50.");

        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(llm, banking, 5);

        ChatResponse response = orchestrator.handle(
                new ChatRequest("how much did I spend across all my accounts?", List.of(), null), token);

        // Response is a normal grounded message turn.
        assertThat(response.type()).isEqualTo("message");

        // toolsUsed reflects the dispatched tools: one getAccounts, one getTransactions per account.
        assertThat(response.toolsUsed())
                .containsExactly("getAccounts", "getTransactions", "getTransactions", "getTransactions");

        // Banking client actually received getAccounts and a getTransactions for EACH account.
        assertThat(banking.callsSeen()).containsExactly(
                "getAccounts",
                "getTransactions:ACC-1001",
                "getTransactions:ACC-1002",
                "getTransactions:ACC-1003");

        // The loop stayed within the configured bound: 3 model round-trips for a bound of 5.
        assertThat(llm.callCount()).isEqualTo(3);
        assertThat(llm.callCount()).isLessThanOrEqualTo(5);
    }

    /**
     * Clarifying-question path (Requirement 14.5): when the model returns a plain
     * text turn (no tool call) that asks the user to disambiguate, the orchestrator
     * returns it as a normal message with no tools used.
     */
    @Test
    void clarifyingQuestionReturnedAsMessageWithNoTools() {
        StubBankingApiClient banking = new StubBankingApiClient();
        StubLlmClient llm = new StubLlmClient().thenText(
                "You have two savings accounts: ACC-1001 and ACC-1003. Which one did you mean?");

        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(llm, banking, 5);

        ChatResponse response = orchestrator.handle(
                new ChatRequest("what's the balance of my savings?", List.of(), null), token);

        assertThat(response.type()).isEqualTo("message");
        assertThat(response.reply())
                .isEqualTo("You have two savings accounts: ACC-1001 and ACC-1003. Which one did you mean?");
        assertThat(response.toolsUsed()).isEmpty();
        assertThat(banking.callsSeen()).isEmpty();
    }

    /**
     * Out-of-scope decline path (Requirement 14.7): a plain text decline from the
     * model is returned as-is as a message, with no tool dispatched.
     */
    @Test
    void outOfScopeDeclineReturnedAsMessage() {
        StubBankingApiClient banking = new StubBankingApiClient();
        StubLlmClient llm = new StubLlmClient().thenText(
                "I can only help with your own banking - balances, accounts, statements, and spending.");

        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(llm, banking, 5);

        ChatResponse response = orchestrator.handle(
                new ChatRequest("what's the weather tomorrow?", List.of(), null), token);

        assertThat(response.type()).isEqualTo("message");
        assertThat(response.reply())
                .isEqualTo("I can only help with your own banking - balances, accounts, statements, and spending.");
        assertThat(response.toolsUsed()).isEmpty();
        assertThat(banking.callsSeen()).isEmpty();
    }
}
