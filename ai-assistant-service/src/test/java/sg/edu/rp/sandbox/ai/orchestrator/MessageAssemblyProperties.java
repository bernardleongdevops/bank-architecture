package sg.edu.rp.sandbox.ai.orchestrator;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import sg.edu.rp.sandbox.ai.banking.AccessToken;
import sg.edu.rp.sandbox.ai.banking.StubBankingApiClient;
import sg.edu.rp.sandbox.ai.dto.ChatMessage;
import sg.edu.rp.sandbox.ai.dto.ChatRequest;
import sg.edu.rp.sandbox.ai.llm.LlmMessage;
import sg.edu.rp.sandbox.ai.llm.StubLlmClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 9: Model input reflects exactly the supplied conversation.
 *
 * <p>For any supplied history, the assembled model input is the system prompt,
 * then exactly that history in order, then the current user message - no extra
 * server-sourced turns and nothing dropped or reordered.
 *
 * <p>Validates: Requirements 2.1, 2.3
 */
@Tag("Feature: ai-assistant-service, Property 9")
class MessageAssemblyProperties {

    @Provide
    Arbitrary<List<ChatMessage>> histories() {
        Arbitrary<String> roles = Arbitraries.of("user", "assistant");
        Arbitrary<String> contents = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
        Arbitrary<ChatMessage> message = Combinators.combine(roles, contents);
        return message.list().ofMaxSize(8);
    }

    @Property(tries = 200)
    void assembledInputIsSystemThenHistoryThenUser(
            @ForAll("histories") List<ChatMessage> history,
            @ForAll @net.jqwik.api.constraints.AlphaChars
            @net.jqwik.api.constraints.StringLength(min = 1, max = 30) String userMessage) {

        StubLlmClient llm = new StubLlmClient().thenText("ok");
        StubBankingApiClient banking = new StubBankingApiClient();
        DefaultChatOrchestrator orchestrator =
                OrchestratorTestSupport.orchestrator(llm, banking, 5);

        ChatRequest request = new ChatRequest(userMessage, history, null);

        // Assemble directly (deterministic) ...
        List<LlmMessage> assembled = orchestrator.assembleMessages(request);

        // Expect: system + each history turn in order + the current user message.
        assertThat(assembled).hasSize(history.size() + 2);
        assertThat(assembled.get(0).role()).isEqualTo("system");
        assertThat(assembled.get(0).content()).isEqualTo(OrchestratorTestSupport.TEST_SYSTEM_PROMPT);

        for (int i = 0; i < history.size(); i++) {
            LlmMessage assembledTurn = assembled.get(i + 1);
            ChatMessage original = history.get(i);
            assertThat(assembledTurn.role()).isEqualTo(original.role());
            assertThat(assembledTurn.content()).isEqualTo(original.content());
        }

        LlmMessage last = assembled.get(assembled.size() - 1);
        assertThat(last.role()).isEqualTo("user");
        assertThat(last.content()).isEqualTo(userMessage);

        // ... and the same ordering is what the model actually received on the first call.
        orchestrator.handle(request, AccessToken.of("tok"));
        List<LlmMessage> sentToModel = llm.firstRequest().messages();
        assertThat(sentToModel).hasSize(history.size() + 2);
        assertThat(sentToModel.get(0).role()).isEqualTo("system");
        assertThat(sentToModel.get(sentToModel.size() - 1).content()).isEqualTo(userMessage);
    }

    // Small helper to combine two arbitraries into a ChatMessage.
    static final class Combinators {
        static Arbitrary<ChatMessage> combine(Arbitrary<String> roles, Arbitrary<String> contents) {
            return net.jqwik.api.Combinators.combine(roles, contents).as(ChatMessage::new);
        }
    }
}
