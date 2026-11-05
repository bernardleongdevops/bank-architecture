package sg.edu.rp.sandbox.ai.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 2.3 - DTO (de)serialization and bean-validation.
 */
class ChatRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void fullRequestDeserializes() throws Exception {
        String json = """
                {
                  "message": "how much is in my savings?",
                  "history": [
                    { "role": "user", "content": "hi" },
                    { "role": "assistant", "content": "Hello! How can I help?" }
                  ],
                  "confirmation": null
                }
                """;
        ChatRequest req = mapper.readValue(json, ChatRequest.class);

        assertThat(req.message()).isEqualTo("how much is in my savings?");
        assertThat(req.historyOrEmpty()).hasSize(2);
        assertThat(req.historyOrEmpty().get(0)).isEqualTo(new ChatMessage("user", "hi"));
        assertThat(req.confirmation()).isNull();
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void confirmationOnlyRequestIsValid() throws Exception {
        String json = """
                {
                  "message": null,
                  "history": [],
                  "confirmation": { "confirmationToken": "tok-123", "confirmed": true }
                }
                """;
        ChatRequest req = mapper.readValue(json, ChatRequest.class);

        assertThat(req.hasMessage()).isFalse();
        assertThat(req.confirmation().confirmed()).isTrue();
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void emptyBodyFailsValidation() throws Exception {
        String json = "{}";
        ChatRequest req = mapper.readValue(json, ChatRequest.class);

        assertThat(validator.validate(req)).isNotEmpty();
    }

    @Test
    void blankMessageWithNoConfirmationFailsValidation() {
        ChatRequest req = new ChatRequest("   ", null, null);
        assertThat(validator.validate(req)).isNotEmpty();
    }
}
