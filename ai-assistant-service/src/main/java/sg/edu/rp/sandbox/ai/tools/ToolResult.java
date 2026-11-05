package sg.edu.rp.sandbox.ai.tools;

/**
 * The outcome of dispatching one tool call. Fed back to the model as a tool-result
 * message.
 *
 * @param toolCallId the id of the originating model tool call (for correlation)
 * @param name       the tool name that was dispatched
 * @param content    JSON (or plain-text) content to feed back to the model
 * @param error      true when this represents a tool error (e.g. malformed args or
 *                   a gateway failure); the content explains the error. A tool error
 *                   is still a valid result returned to the model, never a thrown
 *                   exception.
 */
public record ToolResult(String toolCallId, String name, String content, boolean error) {

    public static ToolResult ok(String toolCallId, String name, String content) {
        return new ToolResult(toolCallId, name, content, false);
    }

    public static ToolResult error(String toolCallId, String name, String message) {
        return new ToolResult(toolCallId, name, message, true);
    }
}
