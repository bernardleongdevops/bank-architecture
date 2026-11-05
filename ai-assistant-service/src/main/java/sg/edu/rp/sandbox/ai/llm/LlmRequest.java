package sg.edu.rp.sandbox.ai.llm;

import sg.edu.rp.sandbox.ai.tools.ToolDefinition;

import java.util.List;

/**
 * A provider-neutral chat-completion request.
 *
 * @param model    the model name (e.g. gpt-4o-mini)
 * @param messages the ordered conversation (system + history + user + tool results)
 * @param tools    the tool definitions the model may call (may be empty)
 */
public record LlmRequest(String model, List<LlmMessage> messages, List<ToolDefinition> tools) {

    public List<ToolDefinition> toolsOrEmpty() {
        return tools == null ? List.of() : tools;
    }
}
