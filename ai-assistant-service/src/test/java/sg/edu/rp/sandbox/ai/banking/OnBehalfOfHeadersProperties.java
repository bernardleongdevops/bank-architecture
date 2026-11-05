package sg.edu.rp.sandbox.ai.banking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import sg.edu.rp.sandbox.ai.config.AiProperties;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 3: Outbound gateway calls are always authenticated on-behalf-of.
 *
 * <p>Exercises the real {@link GatewayBankingApiClient} against a loopback-only JDK
 * {@link HttpServer} that records the headers it receives. For any inbound token,
 * every outbound gateway request must carry {@code Authorization: Bearer <that token>}
 * and the configured {@code X-Sandbox-Key}. No external network is used.
 *
 * <p>Validates: Requirements 4.1, 4.2, 4.3, 13.4
 */
@Tag("Feature: ai-assistant-service, Property 3")
class OnBehalfOfHeadersProperties {

    private static HttpServer server;
    private static GatewayBankingApiClient client;
    private static final String SANDBOX_KEY = "demo-key-123";
    private static final ConcurrentLinkedQueue<ConcurrentHashMap<String, String>> capturedHeaders =
            new ConcurrentLinkedQueue<>();

    @BeforeContainer
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            ConcurrentHashMap<String, String> headers = new ConcurrentHashMap<>();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String key = exchange.getRequestHeaders().getFirst("X-Sandbox-Key");
            if (auth != null) {
                headers.put("Authorization", auth);
            }
            if (key != null) {
                headers.put("X-Sandbox-Key", key);
            }
            capturedHeaders.add(headers);

            // Respond with a valid-but-empty JSON body appropriate to the path.
            // Collection endpoints (accounts list, transactions list) return an array;
            // single-resource endpoints return an object.
            String path = exchange.getRequestURI().getPath();
            boolean isCollection = path.endsWith("/accounts") || path.contains("/transactions/");
            String body = isCollection ? "[]" : "{}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AiProperties props = new AiProperties();
        props.getGateway().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setSandboxKey(SANDBOX_KEY);
        client = new GatewayBankingApiClient(props, new ObjectMapper().findAndRegisterModules());
    }

    @AfterContainer
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Property(tries = 200)
    void everyOutboundCallCarriesBearerAndSandboxKey(
            @ForAll @NotBlank @AlphaChars @StringLength(min = 8, max = 40) String rawToken,
            @ForAll @AlphaChars @StringLength(min = 4, max = 8) String acct) {

        capturedHeaders.clear();
        AccessToken token = AccessToken.of(rawToken);
        String accountNumber = "ACC-" + acct;

        // Exercise each read method; each must attach the headers.
        List<Runnable> calls = new ArrayList<>(List.of(
                () -> client.getCustomerProfile(token),
                () -> client.getAccounts(token),
                () -> client.getAccount(token, accountNumber),
                () -> client.getTransactions(token, accountNumber, null, null),
                () -> client.getTransactions(token, accountNumber, "2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z")));
        calls.forEach(Runnable::run);

        assertThat(capturedHeaders).hasSize(calls.size());
        for (ConcurrentHashMap<String, String> headers : capturedHeaders) {
            assertThat(headers.get("Authorization")).isEqualTo("Bearer " + rawToken);
            assertThat(headers.get("X-Sandbox-Key")).isEqualTo(SANDBOX_KEY);
        }
    }
}
