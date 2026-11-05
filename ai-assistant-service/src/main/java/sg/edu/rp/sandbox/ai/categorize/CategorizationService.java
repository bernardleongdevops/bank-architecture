package sg.edu.rp.sandbox.ai.categorize;

import sg.edu.rp.sandbox.ai.banking.Transaction;
import sg.edu.rp.sandbox.ai.dto.CategorizationResult;

import java.util.List;

/**
 * Spending categorisation. Given a set of transactions over a period, classifies
 * each freeform description into exactly one canonical
 * {@link sg.edu.rp.sandbox.ai.dto.SpendingCategory} (closed set; unknown -&gt; OTHER)
 * and aggregates signed amounts and counts per category.
 */
public interface CategorizationService {

    /**
     * @param txns   the transactions to categorise (may be empty)
     * @param period a human-readable period label for the result (may be null)
     * @return per-category totals and counts; conserves the input amounts and count
     */
    CategorizationResult categorize(List<Transaction> txns, String period);
}
