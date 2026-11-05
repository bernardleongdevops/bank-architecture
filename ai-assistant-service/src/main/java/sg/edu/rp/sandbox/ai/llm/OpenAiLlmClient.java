package sg.edu.rp.sandbox.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import sg.edu.rp.sandbox.ai.config.AiProperties;
import sg.edu.rp.sandbox.ai.tools.ToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link LlmClient} implementation backed by the OpenAI Chat Completions
 * API with tool/function calling.
 *
 * <p><strong>All</strong> OpenAI-specific JSON mapping lives here. The request we
 * send looks like:
 * <pre>
 * {
 *   "model": "gpt-4o-mini",
 *   "messages": [
 *     { "role": "system", "content": "..." },
 *     { "role": "user", "content": "..." },
 *     { "role": "assistant", "content": null,
 *       "tool_calls": [ { "id": "call_1", "type": "function",
 *                         "function": { "name": "getAccounts", "arguments": "{}" } } ] },
 *     { "role": "tool", "tool_call_id": "call_1", "content": "[ ...tool result json... ]" }
 *   ],
 *   "tools": [ { "type": "function",
 *                "function": { "name": "...", "description": "...", "parameters": { ...json schema... } } } ]
 * }
 * </pre>
 *
 * <p>The response we read is {@code choices[0].message}: if it carries
 * {@code tool_calls} we surface those; otherwise we surface {@code content} as the
 * final answer.
 *
 * <p>This bean is only created when {@code ai.openai.api-key} is set, so tests can
 * supply a stub {@link LlmClient} with no network.
 */
@Component
@ConditionalOnProperty(prefix = "ai.openai", name = "api-key")
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);

    private final RestClient restClient;
    private final AiProperties properties;

    public OpenAiLlmClient(AiProperties properties) {
        this.properties = properties;
        // Bounded timeouts so a slow/hung provider degrades to a graceful error
        // rather than blocking the request thread indefinitely.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(properties.getOpenai().getBaseUrl())
                // The API key is set as a Bearer header; it is never logged.
                .defaultHeader("Authorization", "Bearer " + properties.getOpenai().getApiKey())
                .build();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Map<String, Object> body = buildRequestBody(request);
        try {
            JsonNode root = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return parseResponse(root);
        } catch (RestClientException ex) {
            // Never include the API key; only a safe transport-level message.
            log.warn("OpenAI chat-completion call failed: {}", ex.getClass().getSimpleName());
            throw new LlmException("The assistant could not reach the model provider.", ex);
        }
    }

    // ---- request mapping (provider-neutral -> OpenAI wire shape) ------------

    // Package-private for focused unit testing of the outbound wire shape (max_tokens, etc.).
    Map<String, Object> buildRequestBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model() != null ? request.model() : properties.getModel());
        // Guardrail A: bound the output size (and therefore cost/latency) of every call.
        body.put("max_tokens", properties.getMaxOutputTokens());

        List<Map<String, Object>> messages = new ArrayList<>();
        for (LlmMessage m : request.messages()) {
            messages.add(toWireMessage(m));
        }
        body.put("messages", messages);

        List<ToolDefinition> tools = request.toolsOrEmpty();
        if (!tools.isEmpty()) {
            List<Map<String, Object>> wireTools = new ArrayList<>();
            for (ToolDefinition t : tools) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", t.name());
                function.put("description", t.description());
                function.put("parameters", t.parametersSchema());
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("type", "function");
                wrapper.put("function", function);
                wireTools.add(wrapper);
            }
            body.put("tools", wireTools);
        }
        return body;
    }

    private Map<String, Object> toWireMessage(LlmMessage m) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", m.role());
        // content may be null for a pure tool-call assistant turn; OpenAI accepts null.
        wire.put("content", m.content());
        if ("tool".equals(m.role()) && m.toolCallId() != null) {
            wire.put("tool_call_id", m.toolCallId());
        }
        if (!m.toolCallsOrEmpty().isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (LlmToolCall call : m.toolCallsOrEmpty()) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.name());
                function.put("arguments", call.arguments() != null ? call.arguments() : "{}");
                Map<String, Object> callWire = new LinkedHashMap<>();
                callWire.put("id", call.id());
                callWire.put("type", "function");
                callWire.put("function", function);
                calls.add(callWire);
            }
            wire.put("tool_calls", calls);
        }
        return wire;
    }

    // ---- response mapping (OpenAI wire shape -> provider-neutral) -----------

    // Package-private for focused unit testing of the response-usage mapping.
    LlmResponse parseResponse(JsonNode root) {
        if (root == null || !root.has("choices") || root.get("choices").isEmpty()) {
            throw new LlmException("The model returned an empty response.");
        }
        JsonNode message = root.get("choices").get(0).get("message");
        if (message == null) {
            throw new LlmException("The model returned no message.");
        }

        TokenUsage usage = parseUsage(root.get("usage"));

        JsonNode toolCallsNode = message.get("tool_calls");
        if (toolCallsNode != null && toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
            List<LlmToolCall> calls = new ArrayList<>();
            for (JsonNode call : toolCallsNode) {
                String id = text(call, "id");
                JsonNode function = call.get("function");
                String name = function != null ? text(function, "name") : null;
                String arguments = function != null ? text(function, "arguments") : "{}";
                calls.add(new LlmToolCall(id, name, arguments));
            }
            return LlmResponse.toolCalls(calls, usage);
        }

        String content = text(message, "content");
        return LlmResponse.text(content != null ? content : "", usage);
    }

    /**
     * Map the OpenAI {@code usage} object to {@link TokenUsage}; zero when absent
     * (Guardrail A). Reads {@code prompt_tokens}, {@code completion_tokens}, and
     * {@code total_tokens}.
     */
    static TokenUsage parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isNull()) {
            return TokenUsage.zero();
        }
        int prompt = intField(usageNode, "prompt_tokens");
        int completion = intField(usageNode, "completion_tokens");
        int total = intField(usageNode, "total_tokens");
        return TokenUsage.of(prompt, completion, total);
    }

    private static int intField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : 0;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }
}
