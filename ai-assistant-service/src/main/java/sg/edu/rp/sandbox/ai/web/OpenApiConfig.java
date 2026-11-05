package sg.edu.rp.sandbox.ai.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "AI Assistant Service API",
        version = "v1",
        description = "Agentic banking chatbot for the RP Digital Bank Sandbox. "
                + "Relays conversations to OpenAI with tool-calling and executes grounded, "
                + "owner-scoped tools via the gateway."))
@SecurityScheme(
        name = "bearer-jwt",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfig {
}
