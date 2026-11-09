package sg.edu.rp.sandbox.ai.tools;

import java.util.Map;

/**
 * A tool/function definition advertised to the model.
 *
 * <p>Provider-neutral: {@code parametersSchema} is a JSON Schema object (as a map)
 * describing the function parameters. The OpenAI wire wrapping
 * ({@code {"type":"function","function":{...}}}) is applied inside
 * {@code OpenAiLlmClient}.
 *
 * @param name             the function name, e.g. "getAccounts"
 * @param description      a human/model-readable description
 * @param parametersSchema the JSON Schema for the arguments object
 */
public record ToolDefinition(String name, String description, Map<String, Object> parametersSchema) {
}
