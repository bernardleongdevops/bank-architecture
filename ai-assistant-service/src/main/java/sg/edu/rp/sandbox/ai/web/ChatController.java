package sg.edu.rp.sandbox.ai.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sg.edu.rp.sandbox.ai.banking.AccessToken;
import sg.edu.rp.sandbox.ai.dto.ChatRequest;
import sg.edu.rp.sandbox.ai.dto.ChatResponse;
import sg.edu.rp.sandbox.ai.orchestrator.ChatOrchestrator;

/**
 * HTTP entry point. Exposes {@code POST /ai/chat} (served under
 * {@code /api/v1/ai/chat} via the gateway).
 *
 * <p>Requires an authenticated JWT (enforced by the shared resource-server config).
 * It captures the <strong>raw</strong> inbound bearer token from the
 * {@code Authorization} header so the orchestrator can forward it on-behalf-of to
 * the gateway, and extracts the JWT {@code sub} so the orchestrator can bind the
 * Phase 2 confirmation token to this user, then delegates to the orchestrator.
 * Returns a {@link ChatResponse}
 * with HTTP 200 for normal turns; an empty body (no message and no confirmation)
 * fails bean validation and is rejected with HTTP 400 by the exception handler.
 */
@RestController
@RequestMapping("/ai")
public class ChatController {

    private final ChatOrchestrator orchestrator;

    public ChatController(ChatOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Operation(summary = "Chat with the banking assistant",
            security = @SecurityRequirement(name = "bearer-jwt"))
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest body,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @AuthenticationPrincipal Jwt jwt) {

        // Capture the raw inbound bearer token to forward on-behalf-of. The security
        // filter has already validated the JWT, so a well-formed header is present.
        AccessToken onBehalfOf = AccessToken.of(stripBearer(authorization));
        // The JWT subject binds the Phase 2 confirmation token to this user, so a
        // transfer can only ever be confirmed by the user who proposed it.
        String subject = jwt != null ? jwt.getSubject() : null;
        ChatResponse response = orchestrator.handle(body, onBehalfOf, subject);
        return ResponseEntity.ok(response);
    }

    private static String stripBearer(String authorization) {
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return authorization;
    }
}
