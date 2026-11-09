package sg.edu.rp.sandbox.ai.categorize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import sg.edu.rp.sandbox.ai.banking.Transaction;
import sg.edu.rp.sandbox.ai.config.AiProperties;
import sg.edu.rp.sandbox.ai.dto.CategorizationResult;
import sg.edu.rp.sandbox.ai.dto.CategoryTotal;
import sg.edu.rp.sandbox.ai.dto.SpendingCategory;
import sg.edu.rp.sandbox.ai.llm.StubLlmClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7.5 - equivalent-description mapping. With a stubbed classifier, both
 * "pocket money" and "allowance" map to ALLOWANCE and aggregate together.
 */
class ModelCategorizationServiceTest {

    private Transaction txn(String desc, String direction, String amount) {
        return new Transaction("TXN-" + desc, "ACC-1001", direction, new BigDecimal(amount),
                "SGD", desc, null, Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    void pocketMoneyAndAllowanceBothMapToAllowance() {
        List<Transaction> txns = List.of(
                txn("pocket money", "CREDIT", "50.00"),
                txn("allowance", "CREDIT", "30.00"));

        // Stubbed classifier returns ALLOWANCE for both indices in a single call.
        StubLlmClient llm = new StubLlmClient().thenText(
                "[{\"index\":0,\"category\":\"ALLOWANCE\"},{\"index\":1,\"category\":\"ALLOWANCE\"}]");

        ModelCategorizationService service = new ModelCategorizationService(
                llm, new AiProperties(), new ObjectMapper().findAndRegisterModules());

        CategorizationResult result = service.categorize(txns, "September");

        assertThat(result.totals()).hasSize(1);
        CategoryTotal allowance = result.totals().get(0);
        assertThat(allowance.category()).isEqualTo(SpendingCategory.ALLOWANCE);
        assertThat(allowance.count()).isEqualTo(2);
        assertThat(allowance.total()).isEqualByComparingTo("80.00");

        // Single-call batching: the classifier was invoked exactly once for both.
        assertThat(llm.callCount()).isEqualTo(1);
    }

    @Test
    void emptyTransactionsYieldEmptyResultWithoutModelCall() {
        StubLlmClient llm = new StubLlmClient().thenText("[]");
        ModelCategorizationService service = new ModelCategorizationService(
                llm, new AiProperties(), new ObjectMapper().findAndRegisterModules());

        CategorizationResult result = service.categorize(List.of(), "empty");

        assertThat(result.totals()).isEmpty();
        assertThat(llm.callCount()).isZero();
    }
}
