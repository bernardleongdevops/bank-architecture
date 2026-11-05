package sg.edu.rp.sandbox.ai.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ByteArrayResource;
import sg.edu.rp.sandbox.ai.banking.StubBankingApiClient;
import sg.edu.rp.sandbox.ai.categorize.StubCategorizationService;
import sg.edu.rp.sandbox.ai.confirm.ConfirmationTokenService;
import sg.edu.rp.sandbox.ai.config.AiProperties;
import sg.edu.rp.sandbox.ai.llm.LlmClient;
import sg.edu.rp.sandbox.ai.tools.ToolDispatcher;
import sg.edu.rp.sandbox.ai.tools.ToolRegistry;

import java.nio.charset.StandardCharsets;

/**
 * Shared helpers to build a {@link DefaultChatOrchestrator} wired with stub seams
 * for unit/property tests (no network).
 */
final class OrchestratorTestSupport {

    static final String TEST_SYSTEM_PROMPT = "TEST SYSTEM PROMPT";
    static final String TEST_CONFIRMATION_SECRET = "test-confirmation-secret-0123456789";

    private OrchestratorTestSupport() {
    }

    static SystemPromptProvider systemPromptProvider() {
        AiProperties disabled = new AiProperties();
        disabled.setTransfersEnabled(false);
        return new SystemPromptProvider(
                new ByteArrayResource(TEST_SYSTEM_PROMPT.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayResource("TEST TRANSFERS FRAGMENT".getBytes(StandardCharsets.UTF_8)),
                disabled);
    }

    static AiProperties properties(int maxIterations) {
        return properties(maxIterations, false);
    }

    static AiProperties properties(int maxIterations, boolean transfersEnabled) {
        AiProperties props = new AiProperties();
        props.setMaxToolIterations(maxIterations);
        props.setModel("gpt-4o-mini");
        props.setTransfersEnabled(transfersEnabled);
        props.getConfirmation().setSecret(TEST_CONFIRMATION_SECRET);
        props.getConfirmation().setTtlSeconds(300);
        return props;
    }

    static ConfirmationTokenService confirmationTokenService(AiProperties props) {
        return new ConfirmationTokenService(props, new ObjectMapper().findAndRegisterModules());
    }

    /** Phase 1 (transfers disabled) orchestrator - preserves original behaviour. */
    static DefaultChatOrchestrator orchestrator(LlmClient llm, StubBankingApiClient banking, int maxIterations) {
        return orchestrator(llm, banking, properties(maxIterations));
    }

    /** Orchestrator with explicit properties (so tests can enable transfers). */
    static DefaultChatOrchestrator orchestrator(LlmClient llm, StubBankingApiClient banking,
                                                AiProperties props) {
        ToolRegistry registry = new ToolRegistry();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ToolDispatcher dispatcher = new ToolDispatcher(registry, banking,
                new StubCategorizationService(), mapper);
        ConfirmationTokenService tokenService = confirmationTokenService(props);
        return new DefaultChatOrchestrator(llm, dispatcher, registry,
                systemPromptProvider(), props, banking, tokenService, mapper);
    }
}
