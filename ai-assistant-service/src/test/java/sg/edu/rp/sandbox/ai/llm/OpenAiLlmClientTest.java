package sg.edu.rp.sandbox.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import sg.edu.rp.sandbox.ai.config.AiProperties;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 15.4(a) - focused unit tests for the Guardrail A additions to
 * {@link OpenAiLlmClient}: the outbound request carries {@code max_tokens} from
 * {@code ai.max-output-tokens}, and a response {@code usage} object is parsed into
 * {@link TokenUsage}. No real network is used: we exercise the package-private
 * request-body builder and the response parser directly.
 */
class OpenAiLlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private OpenAiLlmClient clientWithMaxOutputTokens(int maxOutputTokens) {
        AiProperties props = new AiProperties();
        props.setModel("gpt-4o-mini");
        props.setMaxOutputTokens(maxOutputTokens);
        // A non-blank key so the (conditional) bean is constructable; never asserted/logged.
        props.getOpenai().setApiKey("test-key");
        return new OpenAiLlmClient(props);
    }

    @Test
    void outboundRequestIncludesMaxTokensFromConfig() {
        OpenAiLlmClient client = clientWithMaxOutputTokens(512);
        LlmRequest request = new LlmRequest("gpt-4o-mini",
                List.of(LlmMessage.user("hello")), List.of());

        Map<String, Object> body = client.buildRequestBody(request);

        assertThat(body).containsEntry("max_tokens", 512);
        assertThat(body).containsEntry("model", "gpt-4o-mini");
    }

    @Test
    void outboundMaxTokensTracksConfiguredValue() {
        OpenAiLlmClient client = clientWithMaxOutputTokens(128);
        LlmRequest request = new LlmRequest("gpt-4o-mini",
                List.of(LlmMessage.user("hi")), List.of());

        Map<String, Object> body = client.buildRequestBody(request);

        assertThat(body).containsEntry("max_tokens", 128);
    }

    @Test
    void responseWithUsageObjectIsParsedIntoTokenUsage() throws Exception {
        OpenAiLlmClient client = clientWithMaxOutputTokens(512);
        String json = """
                {
                  "choices": [
                    { "message": { "role": "assistant", "content": "Your balance is SGD 5,000.00." } }
                  ],
                  "usage": { "prompt_tokens": 42, "completion_tokens": 13, "total_tokens": 55 }
                }
                """;
        JsonNode root = mapper.readTree(json);

        LlmResponse response = client.parseResponse(root);

        assertThat(response.hasToolCalls()).isFalse();
        assertThat(response.content()).contains("SGD 5,000.00");
        assertThat(response.usage()).isEqualTo(new TokenUsage(42, 13, 55));
    }

    @Test
    void responseWithoutUsageObjectYieldsZeroUsage() throws Exception {
        OpenAiLlmClient client = clientWithMaxOutputTokens(512);
        String json = """
                {
                  "choices": [
                    { "message": { "role": "assistant", "content": "Hi." } }
                  ]
                }
                """;
        JsonNode root = mapper.readTree(json);

        LlmResponse response = client.parseResponse(root);

        assertThat(response.usage()).isEqualTo(TokenUsage.zero());
    }

    @Test
    void parseUsageMapsOpenAiFieldsAndDefaultsToZeroWhenAbsent() throws Exception {
        JsonNode present = mapper.readTree(
                "{ \"prompt_tokens\": 10, \"completion_tokens\": 7, \"total_tokens\": 17 }");
        assertThat(OpenAiLlmClient.parseUsage(present)).isEqualTo(new TokenUsage(10, 7, 17));

        assertThat(OpenAiLlmClient.parseUsage(null)).isEqualTo(TokenUsage.zero());
    }
}
