package sg.edu.rp.sandbox.ai.llm;

/**
 * Thrown when a call to the model fails (timeout, transport error, or an upstream
 * error status). The orchestrator maps this to a graceful
 * "temporarily unavailable" response. The OpenAI key is never included in the
 * message.
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
