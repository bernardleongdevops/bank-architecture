package sg.edu.rp.sandbox.ai.dto;

/**
 * Canonical, closed set of spending categories. The model must map every
 * transaction description to exactly one member; anything ambiguous maps to
 * {@link #OTHER}. The model must never invent a category outside this set.
 */
public enum SpendingCategory {
    /** "kopi", "lunch", "dinner", "cafe" */
    FOOD_AND_DINING,
    /** "ntuc", "supermarket", "fairprice" */
    GROCERIES,
    /** "mrt", "grab", "taxi", "bus", "petrol" */
    TRANSPORT,
    /** "shopee", "lazada", "clothes" */
    SHOPPING,
    /** "electricity", "water", "telco", "rent" */
    BILLS_AND_UTILITIES,
    /** "netflix", "cinema", "games" */
    ENTERTAINMENT,
    /** "clinic", "pharmacy", "gym" */
    HEALTH,
    /** internal/external transfers between accounts */
    TRANSFERS,
    /** "salary", "payroll" */
    INCOME_SALARY,
    /** "pocket money", "allowance" */
    ALLOWANCE,
    /** anything that does not clearly map */
    OTHER;

    /**
     * Parses a model-produced category name into a member of this closed set,
     * defaulting to {@link #OTHER} for anything unrecognised (case-insensitive,
     * tolerant of surrounding whitespace).
     *
     * @param raw the raw category string from the model (may be null)
     * @return the matching member, or {@link #OTHER}
     */
    public static SpendingCategory fromModel(String raw) {
        if (raw == null) {
            return OTHER;
        }
        String normalized = raw.trim().toUpperCase().replace(' ', '_').replace('&', '_');
        for (SpendingCategory c : values()) {
            if (c.name().equals(normalized)) {
                return c;
            }
        }
        return OTHER;
    }
}
