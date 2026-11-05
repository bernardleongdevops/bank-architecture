package sg.edu.rp.sandbox.ai.tools;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the tool/function definitions advertised to the model.
 *
 * <p><strong>Phase 1 exposes read-only tools only</strong>:
 * {@code getCustomerProfile}, {@code getAccounts}, {@code getAccount},
 * {@code getTransactions}, {@code categorizeSpending}. Phase 1 exposes NO
 * money-moving tool at all - see Property 4.
 *
 * <p><strong>Phase 2</strong> (only when {@code ai.transfers-enabled=true})
 * additionally exposes {@code proposeTransfer}. {@code proposeTransfer} is
 * <em>not</em> a money-moving tool: it only drafts a {@code TransferIntent} for the
 * user to confirm; it moves no money. Crucially there is <strong>never</strong> a
 * {@code makeTransfer} tool exposed to the model in any phase - execution is
 * backend-gated behind the confirmation handshake (Property 4 still holds with
 * transfers enabled).
 *
 * <p>The parameter schemas mirror the JSON Schemas in {@code design.md} exactly.
 */
@Component
public class ToolRegistry {

    /** Phase 1 tool names (read-only). Kept as a stable, closed set. */
    public static final String GET_CUSTOMER_PROFILE = "getCustomerProfile";
    public static final String GET_ACCOUNTS = "getAccounts";
    public static final String GET_ACCOUNT = "getAccount";
    public static final String GET_TRANSACTIONS = "getTransactions";
    public static final String CATEGORIZE_SPENDING = "categorizeSpending";

    /** Phase 2 proposal tool (drafts an intent; does NOT move money). */
    public static final String PROPOSE_TRANSFER = "proposeTransfer";

    private final List<ToolDefinition> phase1Tools = buildPhase1Tools();
    private final List<ToolDefinition> phase2Tools = buildPhase2Tools();

    /** @return the Phase 1 (read-only) tool definitions. */
    public List<ToolDefinition> phase1Definitions() {
        return phase1Tools;
    }

    /**
     * @param transfersEnabled whether Phase 2 money-movement features are enabled
     * @return the tools exposed to the model for the active phase: the Phase 1 read
     *         tools always, plus {@code proposeTransfer} only when transfers are
     *         enabled. Never includes a {@code makeTransfer} tool.
     */
    public List<ToolDefinition> definitions(boolean transfersEnabled) {
        return transfersEnabled ? phase2Tools : phase1Tools;
    }

    /** @return true if the given name is a known Phase 1 tool. */
    public boolean isPhase1Tool(String name) {
        return phase1Tools.stream().anyMatch(t -> t.name().equals(name));
    }

    private static List<ToolDefinition> buildPhase1Tools() {
        return List.of(
                new ToolDefinition(
                        GET_CUSTOMER_PROFILE,
                        "Get the signed-in customer's own profile record (name, KYC status, profile).",
                        emptyObjectSchema()),
                new ToolDefinition(
                        GET_ACCOUNTS,
                        "List the signed-in customer's own accounts with balances and currency.",
                        emptyObjectSchema()),
                new ToolDefinition(
                        GET_ACCOUNT,
                        "Get one of the signed-in customer's own accounts by account number.",
                        accountNumberSchema()),
                new ToolDefinition(
                        GET_TRANSACTIONS,
                        "Get statement history for one owned account over an optional ISO-8601 date range.",
                        transactionsSchema()),
                new ToolDefinition(
                        CATEGORIZE_SPENDING,
                        "Classify an account's transactions over a period into canonical categories and total each category.",
                        transactionsSchema()));
    }

    private static List<ToolDefinition> buildPhase2Tools() {
        List<ToolDefinition> tools = new java.util.ArrayList<>(buildPhase1Tools());
        tools.add(new ToolDefinition(
                PROPOSE_TRANSFER,
                "PHASE 2. Propose an internal transfer for the user to confirm. This does NOT move money; "
                        + "it only drafts an intent that the user must explicitly confirm.",
                proposeTransferSchema()));
        return List.copyOf(tools);
    }

    private static Map<String, Object> proposeTransferSchema() {
        Map<String, Object> fromAccount = new LinkedHashMap<>();
        fromAccount.put("type", "string");
        fromAccount.put("description", "A source account the user owns, e.g. ACC-1001");

        Map<String, Object> toAccount = new LinkedHashMap<>();
        toAccount.put("type", "string");
        toAccount.put("description", "Destination account, e.g. ACC-2001");

        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("type", "number");
        amount.put("description", "Positive amount in the account currency (SGD)");

        Map<String, Object> description = new LinkedHashMap<>();
        description.put("type", "string");
        description.put("description", "Short free-text note");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("fromAccount", fromAccount);
        properties.put("toAccount", toAccount);
        properties.put("amount", amount);
        properties.put("description", description);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("fromAccount", "toAccount", "amount"));
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> emptyObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> accountNumberSchema() {
        Map<String, Object> accountNumber = new LinkedHashMap<>();
        accountNumber.put("type", "string");
        accountNumber.put("description", "e.g. ACC-1001");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("accountNumber", accountNumber);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("accountNumber"));
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> transactionsSchema() {
        Map<String, Object> accountNumber = new LinkedHashMap<>();
        accountNumber.put("type", "string");
        accountNumber.put("description", "e.g. ACC-1001");

        Map<String, Object> from = new LinkedHashMap<>();
        from.put("type", "string");
        from.put("description", "ISO-8601 start, optional");

        Map<String, Object> to = new LinkedHashMap<>();
        to.put("type", "string");
        to.put("description", "ISO-8601 end, optional");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("accountNumber", accountNumber);
        properties.put("from", from);
        properties.put("to", to);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("accountNumber"));
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }
}
