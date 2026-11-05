package sg.edu.rp.sandbox.ai.orchestrator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import sg.edu.rp.sandbox.ai.config.AiProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads the externalised system prompt and composes it to match the current phase.
 *
 * <p>The base prompt text lives in {@code classpath:system-prompt.txt} so it can be
 * tuned without code changes. When {@code ai.transfers-enabled=true}, an additional
 * fragment ({@code classpath:system-prompt-transfers.txt}) is appended; that fragment
 * explicitly supersedes the base read-only wording and tells the model it may propose a
 * transfer for the user to confirm. When transfers are disabled (the default), the base
 * read-only prompt is returned unchanged.
 *
 * <p>The composed prompt is computed once at construction and cached.
 */
@Component
public class SystemPromptProvider {

    private final String prompt;

    public SystemPromptProvider(@Value("classpath:system-prompt.txt") Resource basePrompt,
                                @Value("classpath:system-prompt-transfers.txt") Resource transfersFragment,
                                AiProperties properties) {
        String base = read(basePrompt, "system-prompt.txt");
        if (properties.isTransfersEnabled()) {
            String fragment = read(transfersFragment, "system-prompt-transfers.txt");
            this.prompt = base + "\n\n" + fragment;
        } else {
            this.prompt = base;
        }
    }

    private static String read(Resource resource, String name) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + name, e);
        }
    }

    /** @return the composed system prompt text for the current phase. */
    public String prompt() {
        return prompt;
    }
}
