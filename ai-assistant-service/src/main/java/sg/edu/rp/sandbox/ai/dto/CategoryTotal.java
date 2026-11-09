package sg.edu.rp.sandbox.ai.dto;

import java.math.BigDecimal;

/**
 * Aggregated spending for one category over a period.
 *
 * @param category the canonical category
 * @param total    signed sum of the transaction amounts in this category
 * @param count    number of transactions in this category
 */
public record CategoryTotal(SpendingCategory category, BigDecimal total, int count) {
}
