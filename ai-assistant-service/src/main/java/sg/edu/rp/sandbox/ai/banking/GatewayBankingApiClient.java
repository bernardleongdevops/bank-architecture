package sg.edu.rp.sandbox.ai.banking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import sg.edu.rp.sandbox.ai.config.AiProperties;
import sg.edu.rp.sandbox.ai.dto.TransferRequest;
import sg.edu.rp.sandbox.ai.dto.TransferResult;
import sg.edu.rp.sandbox.common.error.ApiError;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Default {@link BankingApiClient}: calls the gateway base URL and always attaches
 * the on-behalf-of bearer token and the configured {@code X-Sandbox-Key}.
 *
 * <p>Maps gateway error statuses (401/403/404/409/422/429/5xx) to
 * {@link BankingApiException}, preserving the standard error body
 * {@code { timestamp, status, error, message, path }}. The bearer token is never
 * logged.
 */
@Component
public class GatewayBankingApiClient implements BankingApiClient {

    private static final Logger log = LoggerFactory.getLogger(GatewayBankingApiClient.class);

    private final RestClient restClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    public GatewayBankingApiClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(15).toMillis());
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(properties.getGateway().getBaseUrl())
                .build();
    }

    @Override
    public CustomerProfile getCustomerProfile(AccessToken token) {
        return get(token, "/customers/me", CustomerProfile.class);
    }

    @Override
    public List<Account> getAccounts(AccessToken token) {
        Account[] accounts = get(token, "/accounts", Account[].class);
        return accounts == null ? List.of() : Arrays.asList(accounts);
    }

    @Override
    public Account getAccount(AccessToken token, String accountNumber) {
        return get(token, "/accounts/" + accountNumber, Account.class);
    }

    @Override
    public List<Transaction> getTransactions(AccessToken token, String accountNumber, String from, String to) {
        UriComponentsBuilder uri = UriComponentsBuilder
                .fromPath("/transactions/accounts/" + accountNumber);
        if (from != null && !from.isBlank()) {
            uri.queryParam("from", from);
        }
        if (to != null && !to.isBlank()) {
            uri.queryParam("to", to);
        }
        Transaction[] txns = get(token, uri.build().toUriString(), Transaction[].class);
        return txns == null ? List.of() : Arrays.asList(txns);
    }

    @Override
    public TransferResult makeTransfer(AccessToken token, TransferRequest request) {
        try {
            // Capture the full response so both success statuses (201 completed,
            // 202 held) and error statuses (422/409/403) can be mapped uniformly.
            RestClient.ResponseSpec spec = restClient.post()
                    .uri("/payments/transfers")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value())
                    .header("X-Sandbox-Key", properties.getSandboxKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    // Do not throw on 4xx; map them to a TransferResult instead.
                    .onStatus(status -> status.isError(), (req, res) -> { });
            var entity = spec.toEntity(String.class);
            int status = entity.getStatusCode().value();
            return mapTransfer(status, entity.getBody());
        } catch (RestClientResponseException ex) {
            // Some 4xx may still surface here depending on the client config.
            return mapTransfer(ex.getStatusCode().value(), ex.getResponseBodyAsString());
        } catch (RestClientException ex) {
            log.warn("Transfer call failed at transport level: {}", ex.getClass().getSimpleName());
            return new TransferResult(503, "ERROR",
                    "The transfer could not be completed right now, please try again.");
        }
    }

    /**
     * Map a payment-service response status to a canonical {@link TransferResult}.
     * 201 -> COMPLETED, 202 -> AML_HELD, 422 -> AML_BLOCKED, 409 -> CONFLICT (uses
     * the gateway error body message), 403 -> FORBIDDEN. The bearer token is never
     * logged or echoed.
     */
    TransferResult mapTransfer(int status, String body) {
        return switch (status) {
            case 201 -> new TransferResult(201, "COMPLETED",
                    "Done - your transfer was completed.");
            case 202 -> new TransferResult(202, "AML_HELD",
                    "Your transfer is held for review and hasn't been applied yet.");
            case 422 -> new TransferResult(422, "AML_BLOCKED",
                    "Sorry, this transfer was blocked by compliance checks.");
            case 409 -> new TransferResult(409, "CONFLICT",
                    "Transfer failed: " + errorBodyMessage(body, "there was a conflict with this transfer."));
            case 403 -> new TransferResult(403, "FORBIDDEN",
                    "That account isn't one of yours.");
            default -> new TransferResult(status, "ERROR",
                    "The transfer could not be completed (status " + status + ").");
        };
    }

    /** Extract the {@code message} from a standard gateway error body, or a fallback. */
    private String errorBodyMessage(String body, String fallback) {
        if (body == null || body.isBlank()) {
            return fallback;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode message = node.get("message");
            if (message != null && message.isTextual() && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (Exception ignored) {
            // Non-standard body; fall through.
        }
        return fallback;
    }

    // ---- shared GET with on-behalf-of headers and error mapping -------------

    private <T> T get(AccessToken token, String path, Class<T> type) {
        try {
            return restClient.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value())
                    .header("X-Sandbox-Key", properties.getSandboxKey())
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, res) -> {
                        throw mapError(res.getStatusCode().value(), readBody(res.getBody()), path);
                    })
                    .body(type);
        } catch (BankingApiException ex) {
            throw ex;
        } catch (RestClientException ex) {
            // Transport-level failure; never leak the token.
            log.warn("Gateway call to {} failed: {}", path, ex.getClass().getSimpleName());
            throw new BankingApiException(503, "GATEWAY_UNAVAILABLE",
                    "The banking service is temporarily unavailable.", null);
        }
    }

    private String readBody(java.io.InputStream body) {
        try {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private BankingApiException mapError(int status, String rawBody, String path) {
        ApiError parsed = null;
        if (rawBody != null && !rawBody.isBlank()) {
            try {
                parsed = objectMapper.readValue(rawBody, ApiError.class);
            } catch (Exception ignored) {
                // Non-standard body; fall through with a generic mapping.
            }
        }
        String errorCode = parsed != null && parsed.error() != null ? parsed.error() : defaultCode(status);
        String message = parsed != null && parsed.message() != null
                ? parsed.message()
                : defaultMessage(status);
        return new BankingApiException(status, errorCode, message, parsed);
    }

    private String defaultCode(int status) {
        return switch (status) {
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 422 -> "UNPROCESSABLE";
            case 429 -> "RATE_LIMITED";
            default -> "GATEWAY_ERROR";
        };
    }

    private String defaultMessage(int status) {
        return switch (status) {
            case 401 -> "Your session has expired. Please sign in again.";
            case 403 -> "That account isn't one of yours.";
            case 404 -> "The requested resource was not found.";
            case 409 -> "The request could not be completed due to a conflict.";
            case 422 -> "The request was rejected by compliance checks.";
            case 429 -> "Too many requests right now, please wait a moment.";
            default -> "The banking service returned an error.";
        };
    }
}
