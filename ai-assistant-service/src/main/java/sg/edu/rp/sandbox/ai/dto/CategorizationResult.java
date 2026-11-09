package sg.edu.rp.sandbox.ai.dto;

import java.util.List;

/**
 * The result of categorising an account's transactions over a period.
 *
 * @param totals per-category totals and counts
 * @param period human-readable description of the period covered
 */
public record CategorizationResult(List<CategoryTotal> totals, String period) {
}
