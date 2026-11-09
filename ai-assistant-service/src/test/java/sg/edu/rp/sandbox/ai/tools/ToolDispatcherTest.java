package sg.edu.rp.sandbox.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sg.edu.rp.sandbox.ai.banking.AccessToken;
import sg.edu.rp.sandbox.ai.banking.StubBankingApiClient;
import sg.edu.rp.sandbox.ai.categorize.StubCategorizationService;
import sg.edu.rp.sandbox.ai.dto.SpendingCategory;
import sg.edu.rp.sandbox.ai.llm.LlmToolCall;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 5.4 - unit tests for tool argument parsing and dispatch mapping.
 */
class ToolDispatcherTest {

    private StubBankingApiClient banking;
    private StubCategorizationService categorizer;
    private ToolDispatcher dispatcher;
    private final AccessToken token = AccessToken.of("user-token-abc");

    @BeforeEach
    void setUp() {
        banking = new StubBankingApiClient();
        categorizer = new StubCategorizationService().withFixedCategory(SpendingCategory.FOOD_AND_DINING);
        dispatcher = new ToolDispatcher(new ToolRegistry(), banking, categorizer,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void getAccountsDispatchesToClient() {
        ToolResult result = dispatcher.dispatch(
                new LlmToolCall("c1", "getAccounts", "{}"), token);

        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("ACC-1001");
        assertThat(banking.callsSeen()).contains("getAccounts");
        assertThat(banking.lastToken()).isSameAs(token);
    }

    @Test
    void getAccountMapsAccountNumberArg() {
        ToolResult result = dispatcher.dispatch(
                new LlmToolCall("c2", "getAccount", "{\"accountNumber\":\"ACC-1002\"}"), token);

        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("ACC-1002");
        assertThat(banking.callsSeen()).contains("getAccount:ACC-1002");
    }

    @Test
    void getTransactionsMapsAccountAndDateRange() {
        ToolResult result = dispatcher.dispatch(
                new LlmToolCall("c3", "getTransactions",
                        "{\"accountNumber\":\"ACC-1001\",\"from\":\"2026-09-01T00:00:00Z\",\"to\":\"2026-09-30T00:00:00Z\"}"),
                token);

        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("TXN-1");
        assertThat(banking.callsSeen()).contains("getTransactions:ACC-1001");
    }

    @Test
    void categorizeSpendingFetchesThenCategorizes() {
        ToolResult result = dispatcher.dispatch(
                new LlmToolCall("c4", "categorizeSpending", "{\"accountNumber\":\"ACC-1001\"}"), token);

        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("FOOD_AND_DINING");
        // The dispatcher fetched transactions first, then handed them to the categorizer.
        assertThat(banking.callsSeen()).contains("getTransactions:ACC-1001");
        assertThat(categorizer.lastInput()).isNotEmpty();
    }

    @Test
    void malformedArgsProduceToolErrorNotException() {
        ToolResult result = dispatcher.dispatch(
                new LlmToolCall("c5", "getAccount", "{ this is not json"), token);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).containsIgnoringCase("malformed");
    }

    @Test
    void missingRequiredArgProducesToolError() {
        ToolResult result = dispatcher.dispatch(
                new LlmToolCall("c6", "getAccount", "{}"), token);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("accountNumber");
    }

    @Test
    void unknownToolProducesToolError() {
        ToolResult result = dispatcher.dispatch(
                new LlmToolCall("c7", "makeTransfer", "{\"amount\":100}"), token);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).containsIgnoringCase("unknown");
        // The money-moving tool was never dispatched to the banking client.
        assertThat(banking.callsSeen()).isEmpty();
    }

    @Test
    void gatewayFailureBecomesToolErrorNotException() {
        banking.forceStatus(403, "FORBIDDEN", "That account isn't one of yours.");
        ToolResult result = dispatcher.dispatch(
                new LlmToolCall("c8", "getAccount", "{\"accountNumber\":\"ACC-9999\"}"), token);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("isn't one of yours");
    }
}
