package sg.edu.rp.sandbox.ai.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import sg.edu.rp.sandbox.ai.banking.BankingApiClient;
import sg.edu.rp.sandbox.ai.banking.StubBankingApiClient;
import sg.edu.rp.sandbox.ai.categorize.CategorizationService;
import sg.edu.rp.sandbox.ai.categorize.StubCategorizationService;
import sg.edu.rp.sandbox.ai.llm.LlmClient;
import sg.edu.rp.sandbox.ai.llm.StubLlmClient;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 8.2 - web-slice tests for {@code POST /ai/chat} (Phase 1), using MockMvc +
 * spring-security-test with stubbed seams (no network). Also serves as the
 * context-load test for the whole wiring.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8080/realms/digital-bank"
})
class ChatControllerWebTest {

    @TestConfiguration
    static class StubSeams {
        @Bean
        JwtDecoder jwtDecoder() {
            // Decode any inbound token string into a valid Jwt so the real
            // resource-server filter authenticates using the actual Authorization
            // header (which the controller also reads to forward on-behalf-of).
            JwtDecoder decoder = mock(JwtDecoder.class);
            when(decoder.decode(anyString())).thenAnswer(invocation -> {
                String tokenValue = invocation.getArgument(0);
                return Jwt.withTokenValue(tokenValue)
                        .header("alg", "none")
                        .subject("sub-CUST-ALICE")
                        .claim("customer_ref", "CUST-ALICE")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build();
            });
            return decoder;
        }

        @Bean
        @Primary
        LlmClient stubLlmClient() {
            // getAccounts tool call, then a grounded final reply.
            return new StubLlmClient()
                    .thenToolCall("c1", "getAccounts", "{}")
                    .thenText("Your savings account ACC-1001 has SGD 5000.00.");
        }

        @Bean
        @Primary
        BankingApiClient stubBankingApiClient() {
            return new StubBankingApiClient();
        }

        @Bean
        @Primary
        CategorizationService stubCategorizationService() {
            return new StubCategorizationService();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BankingApiClient bankingApiClient;

    @Test
    void authenticatedRequestReturnsGroundedReply() throws Exception {
        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", "Bearer user-access-token-xyz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"how much in my savings?\",\"history\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("message"))
                .andExpect(jsonPath("$.reply").value("Your savings account ACC-1001 has SGD 5000.00."))
                .andExpect(jsonPath("$.toolsUsed[0]").value("getAccounts"));

        // The stubbed banking client was called with the forwarded raw bearer token.
        StubBankingApiClient stub = (StubBankingApiClient) bankingApiClient;
        assertThat(stub.callsSeen()).contains("getAccounts");
        assertThat(stub.lastToken()).isNotNull();
        assertThat(stub.lastToken().value()).isEqualTo("user-access-token-xyz");
    }

    @Test
    void noTokenIsRejectedWith401() throws Exception {
        mockMvc.perform(post("/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\",\"history\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emptyBodyFailsValidationWith400() throws Exception {
        mockMvc.perform(post("/ai/chat")
                        .header("Authorization", "Bearer user-access-token-xyz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void actuatorHealthIsReachableWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
