package sg.edu.rp.sandbox.ai.dto;

/**
 * One turn of conversation replayed by the (stateless) client.
 *
 * @param role    the message role, one of {@code "user"} or {@code "assistant"}
 * @param content the message text
 */
public record ChatMessage(String role, String content) {
}
