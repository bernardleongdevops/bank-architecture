package sg.edu.rp.sandbox.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Strongly-typed binding for the {@code ai.*} configuration keys.
 *
 * <p>Nothing secret is hardcoded: the OpenAI key is read from the environment
 * (via {@code ai.openai.api-key} bound to {@code OPENAI_API_KEY}) and defaults to
 * empty, the sandbox key and gateway URL come from config, and the model defaults
 * to {@code gpt-4o-mini}.
 */
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** OpenAI chat-completions model, default {@code gpt-4o-mini}. */
    private String model = "gpt-4o-mini";

    /** Maximum number of tool-loop round-trips per chat turn. */
    private int maxToolIterations = 5;

    /**
     * Maximum output tokens per OpenAI chat-completion call ({@code max_tokens}),
     * bounding per-call cost and latency. Default 512 (Guardrail A). This is
     * independent of {@link #maxToolIterations}, which caps round-trips per turn.
     */
    private int maxOutputTokens = 512;

    /**
     * Phase gate for money movement. When {@code false} (the deployed default), the
     * model-facing tool set is exactly the Phase 1 read tools and no transfer can be
     * proposed or executed. When {@code true}, the {@code proposeTransfer} tool is
     * additionally exposed and the confirmation handshake can execute a transfer.
     *
     * <p>{@code proposeTransfer} only drafts an intent - it never moves money - and
     * no {@code makeTransfer} tool is ever exposed to the model in any phase
     * (Property 4).
     */
    private boolean transfersEnabled = false;

    @NestedConfigurationProperty
    private Gateway gateway = new Gateway();

    @NestedConfigurationProperty
    private OpenAi openai = new OpenAi();

    @NestedConfigurationProperty
    private Confirmation confirmation = new Confirmation();

    /** Value sent as the {@code X-Sandbox-Key} header on outbound gateway calls. */
    private String sandboxKey = "demo-key-123";

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxToolIterations() {
        return maxToolIterations;
    }

    public void setMaxToolIterations(int maxToolIterations) {
        this.maxToolIterations = maxToolIterations;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public boolean isTransfersEnabled() {
        return transfersEnabled;
    }

    public void setTransfersEnabled(boolean transfersEnabled) {
        this.transfersEnabled = transfersEnabled;
    }

    public Confirmation getConfirmation() {
        return confirmation;
    }

    public void setConfirmation(Confirmation confirmation) {
        this.confirmation = confirmation;
    }

    public Gateway getGateway() {
        return gateway;
    }

    public void setGateway(Gateway gateway) {
        this.gateway = gateway;
    }

    public OpenAi getOpenai() {
        return openai;
    }

    public void setOpenai(OpenAi openai) {
        this.openai = openai;
    }

    public String getSandboxKey() {
        return sandboxKey;
    }

    public void setSandboxKey(String sandboxKey) {
        this.sandboxKey = sandboxKey;
    }

    /** Gateway (banking API) configuration. */
    public static class Gateway {
        /** Base URL of the gateway API, default {@code http://localhost:8081/api/v1}. */
        private String baseUrl = "http://localhost:8081/api/v1";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    /** OpenAI configuration. The API key is backend-only and never logged. */
    public static class OpenAi {
        /** OpenAI API key, sourced from the {@code OPENAI_API_KEY} env var; empty by default. */
        private String apiKey = "";

        /** OpenAI base URL, default {@code https://api.openai.com/v1}. */
        private String baseUrl = "https://api.openai.com/v1";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    /**
     * Confirmation-token configuration (Phase 2). The {@code secret} signs the
     * self-contained confirmation token; it is backend-only and never logged. When
     * empty, a random per-instance secret is generated at startup (tokens will not
     * survive a restart). {@code ttlSeconds} is the short validity window.
     */
    public static class Confirmation {
        /**
         * HMAC signing secret for confirmation tokens. Sourced from
         * {@code AI_CONFIRMATION_SECRET}; empty by default so a per-instance random
         * secret is generated at startup. The clearly-non-production dev default is
         * supplied via {@code application.yml}, not hardcoded here.
         */
        private String secret = "";

        /** Confirmation token time-to-live in seconds (short-lived), default 300. */
        private long ttlSeconds = 300;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }
}
