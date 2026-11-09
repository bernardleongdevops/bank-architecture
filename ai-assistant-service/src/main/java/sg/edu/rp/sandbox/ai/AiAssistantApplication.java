package sg.edu.rp.sandbox.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import sg.edu.rp.sandbox.ai.config.AiProperties;
import sg.edu.rp.sandbox.security.ResourceServerSecurityConfig;

/**
 * AI Assistant Service - an agentic banking chatbot.
 *
 * <p>Exposes {@code POST /ai/chat} (served under {@code /api/v1/ai/chat} via the
 * gateway). It relays the conversation to OpenAI using tool/function calling and
 * executes tools by calling the existing gateway HTTP APIs on-behalf-of the
 * signed-in user. The OpenAI key stays backend-only and every answer is grounded
 * in real tool results.
 *
 * <p>Imports the shared {@link ResourceServerSecurityConfig} so the inbound JWT is
 * validated against the Keycloak issuer. This service has <strong>no datasource</strong>;
 * {@link DataSourceAutoConfiguration} and {@link HibernateJpaAutoConfiguration} are
 * excluded so the context starts cleanly without a database.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
@EnableConfigurationProperties(AiProperties.class)
@Import(ResourceServerSecurityConfig.class)
public class AiAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiAssistantApplication.class, args);
    }
}
