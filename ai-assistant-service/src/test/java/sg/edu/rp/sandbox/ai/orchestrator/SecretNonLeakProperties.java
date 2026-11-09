package sg.edu.rp.sandbox.ai.orchestrator;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.slf4j.LoggerFactory;
import sg.edu.rp.sandbox.ai.banking.AccessToken;
import sg.edu.rp.sandbox.ai.banking.StubBankingApiClient;
import sg.edu.rp.sandbox.ai.config.AiProperties;
import sg.edu.rp.sandbox.ai.dto.ChatRequest;
import sg.edu.rp.sandbox.ai.dto.ChatResponse;
import sg.edu.rp.sandbox.ai.dto.ConfirmationDecision;
import sg.edu.rp.sandbox.ai.llm.LlmClient;
import sg.edu.rp.sandbox.ai.llm.LlmRequest;
import sg.edu.rp.sandbox.ai.llm.LlmResponse;
import sg.edu.rp.sandbox.ai.llm.LlmToolCall;
import sg.edu.rp.sandbox.ai.llm.StubLlmClient;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 10: Secrets never appear in responses or logs.
 *
 * <p>For arbitrary requests (normal Q&A, transfer proposal, and confirmation), the
 * on-behalf-of bearer token value and the OpenAI key value never appear in the
 * {@link ChatResponse} nor in any log line emitted by the service components
 * (orchestrator, confirmation-token service, banking client).
 *
 * <p>Validates: Requirements 11.2, 11.3
 */
@Tag("Feature: ai-assistant-service, Property 10")
class SecretNonLeakProperties {

    private static final String OPENAI_KEY = "sk-topsecret-openai-key-abcdef0123456789";
    private static final String SUBJECT = "sub-OWNER";

    private ListAppender<ILoggingEvent> attach(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
        return appender;
    }

    /** A model that proposes a transfer when transfers are enabled, else answers. */
    private static final class ScriptedModel implements LlmClient {
        private final boolean propose;

        ScriptedModel(boolean propose) {
            this.propose = propose;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            if (propose) {
                return LlmResponse.toolCalls(List.of(new LlmToolCall(
                        "c1", "proposeTransfer",
                        "{\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2001\",\"amount\":50}")));
            }
            return LlmResponse.text("Your balance looks fine.");
        }
    }

    @Property(tries = 150)
    void bearerAndOpenAiKeyNeverLeakToResponseOrLogs(
            @ForAll @AlphaChars @StringLength(min = 8, max = 40) String bearerValue,
            @ForAll boolean transfersEnabled,
            @ForAll boolean confirmFlow) {

        // Make the generated bearer clearly distinct and recognisable.
        String bearer = "BEARER-" + bearerValue;
        AccessToken token = AccessToken.of(bearer);

        AiProperties props = OrchestratorTestSupport.properties(5, transfersEnabled);
        props.getOpenai().setApiKey(OPENAI_KEY); // the key lives only in config; never surfaced.

        StubBankingApiClient banking = new StubBankingApiClient();
        LlmClient model = new ScriptedModel(transfersEnabled);
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(model, banking, props);

        ListAppender<ILoggingEvent> orchLogs = attach(DefaultChatOrchestrator.class);
        ListAppender<ILoggingEvent> confirmLogs =
                attach(sg.edu.rp.sandbox.ai.confirm.ConfirmationTokenService.class);

        ChatResponse first = orchestrator.handle(
                new ChatRequest("how am I doing?", List.of(), null), token, SUBJECT);
        assertNoSecrets(first, orchLogs, confirmLogs, bearer);

        // If a proposal was produced and we're in the confirm flow, confirm it too.
        if (confirmFlow && "confirm_transfer".equals(first.type())) {
            ChatResponse confirmed = orchestrator.handle(
                    new ChatRequest(null, List.of(),
                            new ConfirmationDecision(first.confirmationToken(), true)),
                    token, SUBJECT);
            assertNoSecrets(confirmed, orchLogs, confirmLogs, bearer);
        }
    }

    private void assertNoSecrets(ChatResponse response,
                                 ListAppender<ILoggingEvent> orchLogs,
                                 ListAppender<ILoggingEvent> confirmLogs,
                                 String bearer) {
        String responseText = Objects.toString(response, "")
                + Objects.toString(response.reply(), "")
                + Objects.toString(response.confirmationToken(), "")
                + Objects.toString(response.humanSummary(), "");
        assertThat(responseText).doesNotContain(bearer);
        assertThat(responseText).doesNotContain(OPENAI_KEY);

        String logs = (orchLogs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining(" ")))
                + " "
                + (confirmLogs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining(" ")));
        assertThat(logs).doesNotContain(bearer);
        assertThat(logs).doesNotContain(OPENAI_KEY);
    }
}
