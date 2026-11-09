package sg.edu.rp.sandbox.ai.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import sg.edu.rp.sandbox.ai.banking.AccessToken;
import sg.edu.rp.sandbox.ai.banking.StubBankingApiClient;
import sg.edu.rp.sandbox.ai.confirm.ConfirmationTokenService;
import sg.edu.rp.sandbox.ai.config.AiProperties;
import sg.edu.rp.sandbox.ai.dto.ChatRequest;
import sg.edu.rp.sandbox.ai.dto.ChatResponse;
import sg.edu.rp.sandbox.ai.dto.ConfirmationDecision;
import sg.edu.rp.sandbox.ai.dto.TransferIntent;
import sg.edu.rp.sandbox.ai.dto.TransferResult;
import sg.edu.rp.sandbox.ai.llm.StubLlmClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 10.4 - unit tests for the Phase 2 confirmation handshake and transfer
 * outcome surfacing.
 *
 * <p>Asserts: expired / garbage / wrong-subject tokens never trigger a transfer and
 * yield an explanatory response; a valid confirmed token triggers
 * {@code makeTransfer} with the exact mapped body; and each payment-service status
 * (201/202/422/409/403) is surfaced in plain language with the correct outcome.
 *
 * <p>Validates: Requirements 7.3, 7.5, 8.2, 8.3, 8.4, 8.5
 */
class ConfirmationHandshakeTest {

    private static final String SUBJECT = "sub-CUST-ALICE";
    private final AccessToken token = AccessToken.of("user-token");

    private AiProperties transfersEnabled() {
        return OrchestratorTestSupport.properties(5, true);
    }

    private ConfirmationTokenService tokenService(AiProperties props) {
        return OrchestratorTestSupport.confirmationTokenService(props);
    }

    private String validTokenFor(AiProperties props, String fromAccount) {
        TransferIntent intent = new TransferIntent(fromAccount, "ACC-2001",
                new BigDecimal("50.00"), "SGD", "lunch");
        return tokenService(props).issue(intent, SUBJECT);
    }

    @Test
    void validConfirmedTokenTriggersMakeTransferWithMappedBody() {
        AiProperties props = transfersEnabled();
        StubBankingApiClient banking = new StubBankingApiClient()
                .withTransferResult(new TransferResult(201, "COMPLETED", "Done - your transfer was completed."));
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(new StubLlmClient(), banking, props);
        String tok = validTokenFor(props, "ACC-1001");

        ChatResponse response = orchestrator.handle(
                new ChatRequest(null, List.of(), new ConfirmationDecision(tok, true)), token, SUBJECT);

        assertThat(banking.transferWasCalled()).isTrue();
        assertThat(banking.lastTransfer().fromAccount()).isEqualTo("ACC-1001");
        assertThat(banking.lastTransfer().toAccount()).isEqualTo("ACC-2001");
        assertThat(banking.lastTransfer().amount()).isEqualByComparingTo("50.00");
        assertThat(banking.lastTransfer().description()).isEqualTo("lunch");
        assertThat(response.type()).isEqualTo("message");
        assertThat(response.transferResult().status()).isEqualTo(201);
        assertThat(response.transferResult().outcome()).isEqualTo("COMPLETED");
    }

    @Test
    void cancelledConfirmationDoesNotTransfer() {
        AiProperties props = transfersEnabled();
        StubBankingApiClient banking = new StubBankingApiClient();
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(new StubLlmClient(), banking, props);
        String tok = validTokenFor(props, "ACC-1001");

        ChatResponse response = orchestrator.handle(
                new ChatRequest(null, List.of(), new ConfirmationDecision(tok, false)), token, SUBJECT);

        assertThat(banking.transferWasCalled()).isFalse();
        assertThat(response.type()).isEqualTo("message");
        assertThat(response.reply()).isEqualTo(DefaultChatOrchestrator.TRANSFER_CANCELLED_MESSAGE);
    }

    @Test
    void garbageTokenIsRefusedAndMovesNoMoney() {
        AiProperties props = transfersEnabled();
        StubBankingApiClient banking = new StubBankingApiClient();
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(new StubLlmClient(), banking, props);

        ChatResponse response = orchestrator.handle(
                new ChatRequest(null, List.of(), new ConfirmationDecision("not-a-real-token", true)),
                token, SUBJECT);

        assertThat(banking.transferWasCalled()).isFalse();
        assertThat(response.type()).isEqualTo("error");
        assertThat(response.reply()).isEqualTo(DefaultChatOrchestrator.CONFIRMATION_EXPIRED_MESSAGE);
    }

    @Test
    void expiredTokenIsRefusedAndMovesNoMoney() {
        AiProperties props = transfersEnabled();
        props.getConfirmation().setTtlSeconds(1);
        // Issue with a clock in the far past so the token is already expired.
        java.time.Clock past = java.time.Clock.fixed(
                java.time.Instant.now().minusSeconds(3600), java.time.ZoneOffset.UTC);
        ConfirmationTokenService pastIssuer =
                new ConfirmationTokenService(props, new ObjectMapper().findAndRegisterModules(), past);
        String expired = pastIssuer.issue(
                new TransferIntent("ACC-1001", "ACC-2001", new BigDecimal("50.00"), "SGD", "x"), SUBJECT);

        StubBankingApiClient banking = new StubBankingApiClient();
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(new StubLlmClient(), banking, props);

        ChatResponse response = orchestrator.handle(
                new ChatRequest(null, List.of(), new ConfirmationDecision(expired, true)), token, SUBJECT);

        assertThat(banking.transferWasCalled()).isFalse();
        assertThat(response.type()).isEqualTo("error");
    }

    @Test
    void wrongSubjectTokenIsRefusedAndMovesNoMoney() {
        AiProperties props = transfersEnabled();
        StubBankingApiClient banking = new StubBankingApiClient();
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(new StubLlmClient(), banking, props);
        String tok = validTokenFor(props, "ACC-1001");

        // A different user confirms the same token: must be rejected.
        ChatResponse response = orchestrator.handle(
                new ChatRequest(null, List.of(), new ConfirmationDecision(tok, true)),
                token, "sub-CUST-MALLORY");

        assertThat(banking.transferWasCalled()).isFalse();
        assertThat(response.type()).isEqualTo("error");
    }

    @Test
    void heldTransfer202SurfacedAsNotApplied() {
        assertOutcomeSurfaced(202, "AML_HELD", "held for review");
    }

    @Test
    void blockedTransfer422SurfacedAsCompliance() {
        assertOutcomeSurfaced(422, "AML_BLOCKED", "compliance");
    }

    @Test
    void conflict409SurfacedUsingMessage() {
        assertOutcomeSurfaced(409, "CONFLICT", "insufficient funds");
    }

    @Test
    void forbidden403SurfacedAsNotYours() {
        assertOutcomeSurfaced(403, "FORBIDDEN", "isn't one of yours");
    }

    private void assertOutcomeSurfaced(int status, String outcome, String expectedSnippet) {
        AiProperties props = transfersEnabled();
        String message = switch (status) {
            case 202 -> "Your transfer is held for review and hasn't been applied yet.";
            case 422 -> "Sorry, this transfer was blocked by compliance checks.";
            case 409 -> "Transfer failed: insufficient funds.";
            case 403 -> "That account isn't one of yours.";
            default -> "x";
        };
        StubBankingApiClient banking = new StubBankingApiClient()
                .withTransferResult(new TransferResult(status, outcome, message));
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(new StubLlmClient(), banking, props);
        String tok = validTokenFor(props, "ACC-1001");

        ChatResponse response = orchestrator.handle(
                new ChatRequest(null, List.of(), new ConfirmationDecision(tok, true)), token, SUBJECT);

        assertThat(banking.transferWasCalled()).isTrue();
        assertThat(response.transferResult().status()).isEqualTo(status);
        assertThat(response.transferResult().outcome()).isEqualTo(outcome);
        assertThat(response.reply().toLowerCase()).contains(expectedSnippet.toLowerCase());
    }
}
