package sg.edu.rp.sandbox.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1.3 - config-binding test for the {@code ai.*} properties and the
 * resource-server issuer/jwks keys. No Spring context is started; we bind directly
 * from an environment so the test is fast and deterministic.
 */
@EnableConfigurationProperties(AiProperties.class)
class AiPropertiesBindingTest {

    private AiProperties bind(Map<String, Object> props) {
        MockEnvironment env = new MockEnvironment();
        props.forEach(env::setProperty);
        Binder binder = new Binder(ConfigurationPropertySources.get(env));
        return binder.bind("ai", Bindable.of(AiProperties.class)).orElseGet(AiProperties::new);
    }

    @Test
    void modelDefaultsToGpt4oMini() {
        AiProperties props = bind(Map.of());
        assertThat(props.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(props.getMaxToolIterations()).isEqualTo(5);
        assertThat(props.getMaxOutputTokens()).isEqualTo(512);
        assertThat(props.getGateway().getBaseUrl()).isEqualTo("http://localhost:8081/api/v1");
        assertThat(props.getSandboxKey()).isEqualTo("demo-key-123");
        assertThat(props.getOpenai().getApiKey()).isEmpty();
        assertThat(props.getOpenai().getBaseUrl()).isEqualTo("https://api.openai.com/v1");
    }

    @Test
    void modelIsOverridable() {
        AiProperties props = bind(Map.of("ai.model", "gpt-4o"));
        assertThat(props.getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void gatewayBaseUrlAndSandboxKeyBind() {
        AiProperties props = bind(Map.of(
                "ai.gateway.base-url", "http://gw:9000/api/v1",
                "ai.sandbox-key", "student-key-456",
                "ai.max-tool-iterations", "7",
                "ai.max-output-tokens", "256"));
        assertThat(props.getGateway().getBaseUrl()).isEqualTo("http://gw:9000/api/v1");
        assertThat(props.getSandboxKey()).isEqualTo("student-key-456");
        assertThat(props.getMaxToolIterations()).isEqualTo(7);
        assertThat(props.getMaxOutputTokens()).isEqualTo(256);
    }

    @Test
    void issuerAndJwksBindFromResourceServerKeys() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                "http://localhost:8080/realms/digital-bank");
        env.setProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                "http://localhost:8080/realms/digital-bank/protocol/openid-connect/certs");

        Binder binder = new Binder(ConfigurationPropertySources.get(env));
        String issuer = binder.bind("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                Bindable.of(String.class)).get();
        String jwks = binder.bind("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                Bindable.of(String.class)).get();

        assertThat(issuer).isEqualTo("http://localhost:8080/realms/digital-bank");
        assertThat(jwks).endsWith("/protocol/openid-connect/certs");
    }
}
