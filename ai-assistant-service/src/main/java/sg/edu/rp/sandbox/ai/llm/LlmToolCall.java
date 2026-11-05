package sg.edu.rp.sandbox.ai.llm;

/**
 * A single tool/function call requested by the model.
 *
 * @param id        provider-assigned id used to correlate the tool result
 * @param name      the tool/function name (e.g. "getAccounts")
 * @param arguments the raw JSON-object arguments string as produced by the model
 */
public record LlmToolCall(String id, String name, String arguments) {
}
