package sg.edu.rp.sandbox.ai.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sg.edu.rp.sandbox.ai.banking.AccessToken;
import sg.edu.rp.sandbox.ai.banking.Account;
import sg.edu.rp.sandbox.ai.banking.BankingApiClient;
import sg.edu.rp.sandbox.ai.banking.BankingApiException;
import sg.edu.rp.sandbox.ai.confirm.ConfirmationTokenService;
import sg.edu.rp.sandbox.ai.config.AiProperties;
import sg.edu.rp.sandbox.ai.dto.ChatMessage;
import sg.edu.rp.sandbox.ai.dto.ChatRequest;
import sg.edu.rp.sandbox.ai.dto.ChatResponse;
import sg.edu.rp.sandbox.ai.dto.ConfirmationDecision;
import sg.edu.rp.sandbox.ai.dto.TransferIntent;
import sg.edu.rp.sandbox.ai.dto.TransferRequest;
import sg.edu.rp.sandbox.ai.dto.TransferResult;
import sg.edu.rp.sandbox.ai.llm.LlmClient;
import sg.edu.rp.sandbox.ai.llm.LlmException;
import sg.edu.rp.sandbox.ai.llm.LlmMessage;
import sg.edu.rp.sandbox.ai.llm.LlmRequest;
import sg.edu.rp.sandbox.ai.llm.LlmResponse;
import sg.edu.rp.sandbox.ai.llm.LlmToolCall;
import sg.edu.rp.sandbox.ai.llm.TokenUsage;
import sg.edu.rp.sandbox.ai.tools.ToolDispatcher;
import sg.edu.rp.sandbox.ai.tools.ToolRegistry;
import sg.edu.rp.sandbox.ai.tools.ToolResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Default orchestrator. Stateless: all conversation context comes from the request.
 *
 * <p>Message assembly (Property 9): {@code system prompt + supplied history (in order)
 * + current user message}. No server-sourced turns are injected.
 *
 * <p>Tool loop (Properties 1, 7): call the model; while it returns tool calls,
 * dispatch each, append the assistant tool-call turn and the tool results, and
 * re-invoke. Stop at a final text answer or when {@code ai.max-tool-iterations} is
 * reached (graceful response). The answer is grounded because every figure the
 * model can cite comes from a real tool result appended to the conversation.
 *
 * <p>Phase gate ({@code ai.transfers-enabled}): when disabled (the default), the
 * model only sees Phase 1 read tools and any confirmation is declined - identical
 * to the original Phase 1 behaviour. When enabled, the model may additionally call
 * {@code proposeTransfer} (which drafts an intent and stops the loop, returning a
 * {@code confirm_transfer} response with a signed token). A transfer executes only
 * on a follow-up request carrying {@code confirmed == true} and a token that
 * verifies for the same subject - never from free-text or injected tool output
 * (Property 2). No {@code makeTransfer} tool is ever exposed to the model.
 */
@Component
public class DefaultChatOrchestrator implements ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatOrchestrator.class);

    static final String UNAVAILABLE_MESSAGE =
            "The assistant is temporarily unavailable, please try again.";
    static final String LOOP_EXHAUSTED_MESSAGE =
            "I couldn't complete that in time. Could you rephrase or narrow your question?";
    static final String TRANSFERS_NOT_SUPPORTED_MESSAGE =
            "Transfers aren't supported yet. I can help you check balances, accounts, "
                    + "statements, and categorise your spending.";
    static final String CONFIRMATION_EXPIRED_MESSAGE =
            "That confirmation expired or wasn't valid - please ask for the transfer again.";
    static final String TRANSFER_CANCELLED_MESSAGE =
            "No problem - I've cancelled that transfer. Nothing was moved.";

    private final LlmClient llmClient;
    private final ToolDispatcher toolDispatcher;
    private final ToolRegistry toolRegistry;
    private final SystemPromptProvider systemPromptProvider;
    private final AiProperties properties;
    private final BankingApiClient bankingApiClient;
    private final ConfirmationTokenService confirmationTokenService;
    private final ObjectMapper objectMapper;

    public DefaultChatOrchestrator(LlmClient llmClient,
                                   ToolDispatcher toolDispatcher,
                                   ToolRegistry toolRegistry,
                                   SystemPromptProvider systemPromptProvider,
                                   AiProperties properties,
                                   BankingApiClient bankingApiClient,
                                   ConfirmationTokenService confirmationTokenService,
                                   ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.toolDispatcher = toolDispatcher;
        this.toolRegistry = toolRegistry;
        this.systemPromptProvider = systemPromptProvider;
        this.properties = properties;
        this.bankingApiClient = bankingApiClient;
        this.confirmationTokenService = confirmationTokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatResponse handle(ChatRequest request, AccessToken onBehalfOf, String subject) {
        // A confirmation decision short-circuits the model entirely: the only
        // state-changing path is this backend-gated handshake (Property 2).
        if (!request.hasMessage() && request.confirmation() != null) {
            return handleConfirmation(request.confirmation(), onBehalfOf, subject);
        }

        List<LlmMessage> messages = assembleMessages(request);
        List<String> toolsUsed = new ArrayList<>();
        int maxIterations = Math.max(1, properties.getMaxToolIterations());
        boolean transfersEnabled = properties.isTransfersEnabled();
        // Accumulate token usage across every model call made during this turn.
        TokenUsage summedUsage = TokenUsage.zero();
        int iterations = 0;

        try {
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                iterations++;
                LlmResponse response = llmClient.complete(
                        new LlmRequest(properties.getModel(), messages,
                                toolRegistry.definitions(transfersEnabled)));
                summedUsage = summedUsage.plus(response.usageOrZero());

                if (!response.hasToolCalls()) {
                    // Final text answer.
                    logTurn(buildTurnLog(toolsUsed, iterations, TurnLog.Outcome.ANSWERED, summedUsage));
                    return ChatResponse.message(safeText(response.content()), List.copyOf(toolsUsed));
                }

                List<LlmToolCall> calls = response.toolCallsOrEmpty();

                // Phase 2: a proposeTransfer call stops the loop and returns a
                // confirm_transfer response. It dispatches NO banking write and never
                // re-invokes the model with a makeTransfer tool (none exists).
                Optional<LlmToolCall> propose = calls.stream()
                        .filter(c -> transfersEnabled && ToolRegistry.PROPOSE_TRANSFER.equals(c.name()))
                        .findFirst();
                if (propose.isPresent()) {
                    toolsUsed.add(ToolRegistry.PROPOSE_TRANSFER);
                    logTurn(buildTurnLog(toolsUsed, iterations, TurnLog.Outcome.ANSWERED, summedUsage));
                    return buildProposal(propose.get(), onBehalfOf, subject, toolsUsed);
                }

                // Append the assistant turn that requested the tools, then each tool result,
                // so the model sees the grounded data on the next round.
                messages.add(LlmMessage.assistantToolCalls(calls));
                for (LlmToolCall call : calls) {
                    ToolResult result = toolDispatcher.dispatch(call, onBehalfOf);
                    toolsUsed.add(call.name());
                    messages.add(LlmMessage.toolResult(call.id(), result.content()));
                }
            }
            // Bound reached without a final text answer: terminate gracefully.
            log.info("Tool loop reached the {}-iteration bound; returning a graceful response.", maxIterations);
            logTurn(buildTurnLog(toolsUsed, iterations, TurnLog.Outcome.LOOP_EXHAUSTED, summedUsage));
            return ChatResponse.message(LOOP_EXHAUSTED_MESSAGE, List.copyOf(toolsUsed));
        } catch (LlmException ex) {
            log.warn("Model call failed: {}", ex.getClass().getSimpleName());
            logTurn(buildTurnLog(toolsUsed, iterations, TurnLog.Outcome.LLM_ERROR, summedUsage));
            return ChatResponse.error(UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * Build a {@code confirm_transfer} response from a proposeTransfer tool call.
     *
     * <p>Resolves the source account currency from the user's owned accounts and
     * validates ownership. If the source isn't an owned account, returns a normal
     * message and issues NO token. Otherwise mints a signed, short-lived
     * confirmation token bound to the intent and subject. Moves no money.
     */
    private ChatResponse buildProposal(LlmToolCall call, AccessToken onBehalfOf,
                                       String subject, List<String> toolsUsed) {
        JsonNode args = parseArgs(call.arguments());
        String fromAccount = text(args, "fromAccount");
        String toAccount = text(args, "toAccount");
        BigDecimal amount = decimal(args, "amount");
        String description = text(args, "description");

        if (fromAccount == null || toAccount == null || amount == null
                || amount.signum() <= 0) {
            return ChatResponse.message(
                    "I couldn't draft that transfer - I need a source account, a destination "
                            + "account, and a positive amount. Could you restate it?",
                    List.copyOf(toolsUsed));
        }

        // Validate the source is an account the user actually owns, and resolve its
        // currency. Ownership is additionally enforced by payment-service on execute.
        Account source;
        try {
            source = findOwnedAccount(onBehalfOf, fromAccount);
        } catch (BankingApiException ex) {
            return ChatResponse.message(
                    "I couldn't look up your accounts just now: " + ex.getMessage(),
                    List.copyOf(toolsUsed));
        }
        if (source == null) {
            return ChatResponse.message(
                    "The account " + fromAccount + " isn't one of yours, so I can't transfer from it.",
                    List.copyOf(toolsUsed));
        }

        String currency = source.currency() != null ? source.currency() : "SGD";
        TransferIntent intent = new TransferIntent(fromAccount, toAccount, amount, currency, description);
        String token = confirmationTokenService.issue(intent, subject);
        String humanSummary = "Transfer " + currency + " " + amount.toPlainString()
                + " from " + fromAccount + " to " + toAccount + "?";
        return new ChatResponse("confirm_transfer", null, intent, token, humanSummary,
                null, List.copyOf(toolsUsed));
    }

    /**
     * Handle a confirmation decision. Executes a transfer if and only if
     * {@code confirmed == true} and the token verifies (valid signature, unexpired,
     * subject match). Otherwise refuses/cancels without moving money (Property 2).
     */
    private ChatResponse handleConfirmation(ConfirmationDecision decision,
                                            AccessToken onBehalfOf, String subject) {
        // Transfers disabled: behave exactly as Phase 1 did (no execution at all).
        if (!properties.isTransfersEnabled()) {
            logTurn(new TurnLog(List.of(), 0, TurnLog.Outcome.ANSWERED, TokenUsage.zero()));
            return ChatResponse.message(TRANSFERS_NOT_SUPPORTED_MESSAGE, List.of());
        }

        if (!decision.confirmed()) {
            logTurn(new TurnLog(List.of(), 0, TurnLog.Outcome.ANSWERED, TokenUsage.zero()));
            return ChatResponse.message(TRANSFER_CANCELLED_MESSAGE, List.of());
        }

        Optional<TransferIntent> verified =
                confirmationTokenService.verify(decision.confirmationToken(), subject);
        if (verified.isEmpty()) {
            // Invalid / expired / wrong-subject token: refuse, move no money.
            logTurn(new TurnLog(List.of(), 0, TurnLog.Outcome.ANSWERED, TokenUsage.zero()));
            return ChatResponse.error(CONFIRMATION_EXPIRED_MESSAGE);
        }

        TransferIntent intent = verified.get();
        TransferRequest req = new TransferRequest(
                intent.fromAccount(), intent.toAccount(), intent.amount(), intent.description());
        TransferResult result;
        try {
            result = bankingApiClient.makeTransfer(onBehalfOf, req);
        } catch (BankingApiException ex) {
            // Any mapped failure the client chose to raise: surface plainly.
            logTurn(new TurnLog(List.of(), 1, TurnLog.Outcome.TOOL_ERROR, TokenUsage.zero()));
            TransferResult failed = new TransferResult(ex.status(), "ERROR", ex.getMessage());
            return new ChatResponse("message", ex.getMessage(), null, null, null, failed, List.of());
        }
        logTurn(new TurnLog(List.of(), 1, TurnLog.Outcome.ANSWERED, TokenUsage.zero()));
        return new ChatResponse("message", result.message(), null, null, null, result, List.of());
    }

    private Account findOwnedAccount(AccessToken onBehalfOf, String accountNumber) {
        for (Account account : bankingApiClient.getAccounts(onBehalfOf)) {
            if (account.accountNumber() != null && account.accountNumber().equals(accountNumber)) {
                return account;
            }
        }
        return null;
    }

    private JsonNode parseArgs(String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return objectMapper.createObjectNode();
            }
            JsonNode node = objectMapper.readTree(raw);
            return node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }

    private static BigDecimal decimal(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            if (node.isTextual() && !node.asText().isBlank()) {
                return new BigDecimal(node.asText().trim());
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    /**
     * Build the structured per-turn observability record (Guardrail A). Pure and
     * package-private so unit tests can assert its fields without parsing logs. The
     * record carries tool NAMES and counts only - never secrets, message content, or
     * tool result bodies (Requirement 11.3 / Property 10).
     */
    TurnLog buildTurnLog(List<String> toolsDispatched, int iterations,
                         TurnLog.Outcome outcome, TokenUsage summedUsage) {
        return new TurnLog(List.copyOf(toolsDispatched), iterations, outcome, summedUsage);
    }

    private void logTurn(TurnLog turnLog) {
        // One INFO record per turn; toString() is redaction-safe by construction.
        log.info("chat turn complete: {}", turnLog);
    }

    /**
     * Assemble the model input: system prompt, then the supplied history in order,
     * then the current user message. (Property 9)
     */
    List<LlmMessage> assembleMessages(ChatRequest request) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(systemPromptProvider.prompt()));
        for (ChatMessage turn : request.historyOrEmpty()) {
            String role = "assistant".equals(turn.role()) ? "assistant" : "user";
            messages.add(new LlmMessage(role, turn.content(), List.of(), null));
        }
        if (request.hasMessage()) {
            messages.add(LlmMessage.user(request.message()));
        }
        return messages;
    }

    private static String safeText(String content) {
        return content == null || content.isBlank()
                ? "I don't have anything to add."
                : content;
    }
}
