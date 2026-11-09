package sg.edu.rp.sandbox.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sg.edu.rp.sandbox.ai.banking.AccessToken;
import sg.edu.rp.sandbox.ai.banking.BankingApiClient;
import sg.edu.rp.sandbox.ai.banking.BankingApiException;
import sg.edu.rp.sandbox.ai.banking.Transaction;
import sg.edu.rp.sandbox.ai.categorize.CategorizationService;
import sg.edu.rp.sandbox.ai.dto.CategorizationResult;
import sg.edu.rp.sandbox.ai.llm.LlmToolCall;

import java.util.List;

/**
 * Maps a model tool call (name + JSON arguments) to the matching
 * {@link BankingApiClient} / {@link CategorizationService} call.
 *
 * <p>Design guarantees:
 * <ul>
 *   <li>Only Phase 1 read tools are dispatchable; an unknown tool yields a tool
 *       <em>error</em> result (never an exception).</li>
 *   <li>Malformed or missing arguments yield a tool error result, not an
 *       exception, so the loop can continue and the model can re-ask.</li>
 *   <li>Gateway failures ({@link BankingApiException}) are surfaced as tool error
 *       results carrying the plain-language message.</li>
 * </ul>
 */
@Component
public class ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);

    private final ToolRegistry registry;
    private final BankingApiClient bankingApiClient;
    private final CategorizationService categorizationService;
    private final ObjectMapper objectMapper;

    public ToolDispatcher(ToolRegistry registry,
                          BankingApiClient bankingApiClient,
                          CategorizationService categorizationService,
                          ObjectMapper objectMapper) {
        this.registry = registry;
        this.bankingApiClient = bankingApiClient;
        this.categorizationService = categorizationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Dispatch one tool call on-behalf-of the given token.
     *
     * @return a {@link ToolResult}, always non-null; errors are represented as
     *         error results rather than thrown exceptions.
     */
    public ToolResult dispatch(LlmToolCall call, AccessToken token) {
        String name = call.name();
        if (name == null || !registry.isPhase1Tool(name)) {
            return ToolResult.error(call.id(), name,
                    "Unknown or unavailable tool: " + name);
        }

        JsonNode args;
        try {
            String raw = call.arguments();
            args = (raw == null || raw.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(raw);
            if (!args.isObject()) {
                return ToolResult.error(call.id(), name, "Tool arguments must be a JSON object.");
            }
        } catch (Exception ex) {
            return ToolResult.error(call.id(), name, "Malformed tool arguments: not valid JSON.");
        }

        try {
            return switch (name) {
                case ToolRegistry.GET_CUSTOMER_PROFILE ->
                        ToolResult.ok(call.id(), name, toJson(bankingApiClient.getCustomerProfile(token)));
                case ToolRegistry.GET_ACCOUNTS ->
                        ToolResult.ok(call.id(), name, toJson(bankingApiClient.getAccounts(token)));
                case ToolRegistry.GET_ACCOUNT -> dispatchGetAccount(call, token, args);
                case ToolRegistry.GET_TRANSACTIONS -> dispatchGetTransactions(call, token, args);
                case ToolRegistry.CATEGORIZE_SPENDING -> dispatchCategorizeSpending(call, token, args);
                default -> ToolResult.error(call.id(), name, "Unhandled tool: " + name);
            };
        } catch (BankingApiException ex) {
            // Surface a plain-language error to the model; do not throw.
            return ToolResult.error(call.id(), name, ex.getMessage());
        } catch (Exception ex) {
            log.warn("Tool {} failed unexpectedly: {}", name, ex.getClass().getSimpleName());
            return ToolResult.error(call.id(), name, "The tool could not be completed.");
        }
    }

    private ToolResult dispatchGetAccount(LlmToolCall call, AccessToken token, JsonNode args) {
        String accountNumber = requiredString(args, "accountNumber");
        if (accountNumber == null) {
            return ToolResult.error(call.id(), call.name(), "Missing required argument: accountNumber");
        }
        return ToolResult.ok(call.id(), call.name(),
                toJson(bankingApiClient.getAccount(token, accountNumber)));
    }

    private ToolResult dispatchGetTransactions(LlmToolCall call, AccessToken token, JsonNode args) {
        String accountNumber = requiredString(args, "accountNumber");
        if (accountNumber == null) {
            return ToolResult.error(call.id(), call.name(), "Missing required argument: accountNumber");
        }
        String from = optionalString(args, "from");
        String to = optionalString(args, "to");
        List<Transaction> txns = bankingApiClient.getTransactions(token, accountNumber, from, to);
        return ToolResult.ok(call.id(), call.name(), toJson(txns));
    }

    private ToolResult dispatchCategorizeSpending(LlmToolCall call, AccessToken token, JsonNode args) {
        String accountNumber = requiredString(args, "accountNumber");
        if (accountNumber == null) {
            return ToolResult.error(call.id(), call.name(), "Missing required argument: accountNumber");
        }
        String from = optionalString(args, "from");
        String to = optionalString(args, "to");
        List<Transaction> txns = bankingApiClient.getTransactions(token, accountNumber, from, to);
        String period = buildPeriod(from, to);
        CategorizationResult result = categorizationService.categorize(txns, period);
        return ToolResult.ok(call.id(), call.name(), toJson(result));
    }

    private static String buildPeriod(String from, String to) {
        if (from == null && to == null) {
            return "all time";
        }
        return (from != null ? from : "the beginning") + " to " + (to != null ? to : "now");
    }

    private static String requiredString(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }

    private static String optionalString(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
