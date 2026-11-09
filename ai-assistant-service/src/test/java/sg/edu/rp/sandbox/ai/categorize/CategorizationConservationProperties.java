package sg.edu.rp.sandbox.ai.categorize;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import sg.edu.rp.sandbox.ai.banking.Transaction;
import sg.edu.rp.sandbox.ai.config.AiProperties;
import sg.edu.rp.sandbox.ai.dto.CategorizationResult;
import sg.edu.rp.sandbox.ai.dto.CategoryTotal;
import sg.edu.rp.sandbox.ai.llm.LlmClient;
import sg.edu.rp.sandbox.ai.llm.LlmRequest;
import sg.edu.rp.sandbox.ai.llm.LlmResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 6: Categorisation conserves amounts and count.
 *
 * <p>For any transactions and any model output, the sum of per-category totals
 * equals the sum of the input (signed) amounts and the sum of per-category counts
 * equals the input transaction count - nothing dropped, duplicated, or invented.
 *
 * <p>Validates: Requirements 6.4, 6.5
 */
@Tag("Feature: ai-assistant-service, Property 6")
class CategorizationConservationProperties {

    /** A model that emits arbitrary (possibly wrong) classifications. */
    private static final class ArbitraryModel implements LlmClient {
        private final String content;

        ArbitraryModel(String content) {
            this.content = content;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            return LlmResponse.text(content);
        }
    }

    @Provide
    Arbitrary<List<Transaction>> transactions() {
        Arbitrary<String> descriptions = Arbitraries.of(
                "kopi", "grab", "ntuc", "salary", "pocket money", "netflix", "???", "");
        Arbitrary<String> directions = Arbitraries.of("DEBIT", "CREDIT");
        Arbitrary<Integer> cents = Arbitraries.integers().between(0, 1000000);
        Arbitrary<Transaction> txn = Combinators.combine(descriptions, directions, cents)
                .as((desc, dir, c) -> new Transaction(
                        "TXN", "ACC-1001", dir, new BigDecimal(c).movePointLeft(2),
                        "SGD", desc, null, Instant.parse("2026-09-01T00:00:00Z")));
        return txn.list().ofMinSize(0).ofMaxSize(15);
    }

    @Provide
    Arbitrary<String> modelOutputs() {
        return Arbitraries.of(
                "[]",
                "[{\"index\":0,\"category\":\"FOOD_AND_DINING\"},{\"index\":1,\"category\":\"TRANSPORT\"}]",
                "garbage",
                "[{\"index\":0,\"category\":\"INVALID\"}]");
    }

    @Property(tries = 200)
    void totalsAndCountAreConserved(
            @ForAll("transactions") List<Transaction> txns,
            @ForAll("modelOutputs") String modelOutput) {

        ModelCategorizationService service = new ModelCategorizationService(
                new ArbitraryModel(modelOutput), new AiProperties(),
                new ObjectMapper().findAndRegisterModules());

        CategorizationResult result = service.categorize(txns, "test");

        BigDecimal expectedTotal = txns.stream()
                .map(Transaction::signedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actualTotal = result.totals().stream()
                .map(CategoryTotal::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int actualCount = result.totals().stream()
                .mapToInt(CategoryTotal::count)
                .sum();

        assertThat(actualTotal).isEqualByComparingTo(expectedTotal);
        assertThat(actualCount).isEqualTo(txns.size());
    }
}
