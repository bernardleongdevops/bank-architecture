package sg.edu.rp.sandbox.ai.banking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import sg.edu.rp.sandbox.ai.config.AiProperties;
import sg.edu.rp.sandbox.ai.dto.TransferResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 8 (focused): the payment-service status -> canonical outcome mapping in
 * {@link GatewayBankingApiClient#mapTransfer(int, String)}. No network is used; we
 * exercise the mapping directly.
 *
 * <p>Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 9.5
 */
class GatewayTransferMappingTest {

    private GatewayBankingApiClient client() {
        AiProperties props = new AiProperties();
        return new GatewayBankingApiClient(props, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void status201MapsToCompleted() {
        TransferResult r = client().mapTransfer(201, "{}");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.outcome()).isEqualTo("COMPLETED");
        assertThat(r.message()).isNotBlank();
    }

    @Test
    void status202MapsToHeld() {
        TransferResult r = client().mapTransfer(202, "{}");
        assertThat(r.outcome()).isEqualTo("AML_HELD");
        assertThat(r.message().toLowerCase()).contains("held");
    }

    @Test
    void status422MapsToBlocked() {
        TransferResult r = client().mapTransfer(422, "{}");
        assertThat(r.outcome()).isEqualTo("AML_BLOCKED");
        assertThat(r.message().toLowerCase()).contains("compliance");
    }

    @Test
    void status409PreservesErrorBodyMessage() {
        String body = """
                { "timestamp":"2026-01-01T00:00:00Z", "status":409, "error":"INSUFFICIENT_FUNDS",
                  "message":"Insufficient funds in ACC-1001.", "path":"/payments/transfers" }
                """;
        TransferResult r = client().mapTransfer(409, body);
        assertThat(r.outcome()).isEqualTo("CONFLICT");
        assertThat(r.message()).contains("Insufficient funds in ACC-1001.");
    }

    @Test
    void status403MapsToForbidden() {
        TransferResult r = client().mapTransfer(403, "{}");
        assertThat(r.outcome()).isEqualTo("FORBIDDEN");
        assertThat(r.message().toLowerCase()).contains("isn't one of yours");
    }
}
