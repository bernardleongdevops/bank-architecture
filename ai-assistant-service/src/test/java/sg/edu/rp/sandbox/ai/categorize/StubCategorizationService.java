package sg.edu.rp.sandbox.ai.categorize;

import sg.edu.rp.sandbox.ai.banking.Transaction;
import sg.edu.rp.sandbox.ai.dto.CategorizationResult;
import sg.edu.rp.sandbox.ai.dto.CategoryTotal;
import sg.edu.rp.sandbox.ai.dto.SpendingCategory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Test fixture {@link CategorizationService} that performs a deterministic, offline
 * categorisation: every supplied transaction is bucketed into {@link #fixedCategory}
 * (default OTHER). Useful for dispatcher/orchestrator tests that need a predictable,
 * network-free categorisation.
 */
public class StubCategorizationService implements CategorizationService {

    private SpendingCategory fixedCategory = SpendingCategory.OTHER;
    private List<Transaction> lastInput = List.of();

    public StubCategorizationService withFixedCategory(SpendingCategory category) {
        this.fixedCategory = category;
        return this;
    }

    public List<Transaction> lastInput() {
        return lastInput;
    }

    @Override
    public CategorizationResult categorize(List<Transaction> txns, String period) {
        this.lastInput = List.copyOf(txns);
        BigDecimal total = txns.stream()
                .map(Transaction::signedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<CategoryTotal> totals = new ArrayList<>();
        if (!txns.isEmpty()) {
            totals.add(new CategoryTotal(fixedCategory, total, txns.size()));
        }
        return new CategorizationResult(totals, period);
    }
}
