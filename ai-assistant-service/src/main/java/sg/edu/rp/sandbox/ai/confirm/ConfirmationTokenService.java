package sg.edu.rp.sandbox.ai.confirm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sg.edu.rp.sandbox.ai.config.AiProperties;
import sg.edu.rp.sandbox.ai.dto.TransferIntent;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mints and verifies the self-contained, signed, short-lived confirmation token
 * that gates every transfer (Property 2, Requirements 7.1-7.3).
 *
 * <p><strong>Stateless by design.</strong> No intent is stored server-side; the
 * token <em>is</em> the integrity mechanism. The token carries a canonical,
 * Base64URL-encoded JSON payload (the claims) plus an HMAC-SHA256 signature over
 * that exact payload. On {@link #verify}, the signature is recomputed and compared
 * in constant time, the expiry is checked, and the subject claim must match the
 * caller's subject - so a token issued for one user can never be confirmed by
 * another, and a token cannot be forged or mutated without the signing secret.
 *
 * <p>The signing secret and the token value are secrets: they are never logged and
 * never returned in a response (Requirement 11.3 / Property 10). The only thing
 * logged here is a one-time startup warning when no secret was configured.
 */
@Component
public class ConfirmationTokenService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationTokenService.class);
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    private final byte[] secretKey;
    private final long ttlSeconds;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ConfirmationTokenService(AiProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    /** Test/advanced seam: inject a fixed clock to exercise expiry deterministically. */
    public ConfirmationTokenService(AiProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.ttlSeconds = Math.max(1, properties.getConfirmation().getTtlSeconds());
        String configured = properties.getConfirmation().getSecret();
        if (configured == null || configured.isBlank()) {
            // Generate a random per-instance secret; confirmations won't survive a
            // restart. Warn once at startup. Never log the generated secret value.
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secretKey = random;
            log.warn("No ai.confirmation.secret configured; generated a random per-instance "
                    + "signing secret. Confirmation tokens will NOT survive a restart. Set "
                    + "AI_CONFIRMATION_SECRET for a stable, production-grade secret.");
        } else {
            this.secretKey = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Issue a signed confirmation token binding the given intent to the subject.
     *
     * @param intent  the proposed transfer (fromAccount/toAccount/amount/currency/description)
     * @param subject the token owner's subject (the JWT {@code sub} claim)
     * @return an opaque token string of the form {@code <payloadB64>.<sigB64>}
     */
    public String issue(TransferIntent intent, String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        long exp = clock.instant().plusSeconds(ttlSeconds).getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subject);
        claims.put("fromAccount", intent.fromAccount());
        claims.put("toAccount", intent.toAccount());
        claims.put("amount", intent.amount() == null ? null : intent.amount().toPlainString());
        claims.put("currency", intent.currency());
        claims.put("description", intent.description());
        claims.put("exp", exp);

        byte[] payload = serialize(claims);
        String payloadB64 = B64.encodeToString(payload);
        String sigB64 = B64.encodeToString(sign(payloadB64.getBytes(StandardCharsets.US_ASCII)));
        return payloadB64 + "." + sigB64;
    }

    /**
     * Verify a token for the given subject.
     *
     * @return the reconstructed {@link TransferIntent} if (and only if) the token is
     *         well-formed, its signature is valid, it is unexpired, and its subject
     *         matches {@code subject}; otherwise {@link Optional#empty()}.
     */
    public Optional<TransferIntent> verify(String token, String subject) {
        if (token == null || subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot >= token.length() - 1) {
            return Optional.empty();
        }
        String payloadB64 = token.substring(0, dot);
        String sigB64 = token.substring(dot + 1);

        // Compare the signature as the exact canonical Base64URL STRING we would
        // have produced, not as decoded bytes. Base64URL is non-canonical in the
        // final (partial) group - several distinct strings decode to the same bytes
        // - so a byte-wise compare would accept a mutated token string (token
        // malleability). A constant-time string compare against our deterministic,
        // unpadded encoding makes the token non-malleable: any change to the
        // signature characters is rejected (Property 2).
        String expectedSigB64 = B64.encodeToString(sign(payloadB64.getBytes(StandardCharsets.US_ASCII)));
        if (!constantTimeEquals(
                expectedSigB64.getBytes(StandardCharsets.US_ASCII),
                sigB64.getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }

        Map<String, Object> claims;
        try {
            byte[] payload = B64_DEC.decode(payloadB64);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(payload, Map.class);
            claims = parsed;
        } catch (Exception ex) {
            return Optional.empty();
        }

        // Expiry check.
        Object expClaim = claims.get("exp");
        long exp = expClaim instanceof Number n ? n.longValue() : -1L;
        if (exp < 0 || Instant.now(clock).getEpochSecond() > exp) {
            return Optional.empty();
        }

        // Subject binding: the token can only be confirmed by its owner.
        if (!subject.equals(String.valueOf(claims.get("sub")))) {
            return Optional.empty();
        }

        return Optional.of(new TransferIntent(
                asString(claims.get("fromAccount")),
                asString(claims.get("toAccount")),
                asBigDecimal(claims.get("amount")),
                asString(claims.get("currency")),
                asString(claims.get("description"))));
    }

    private byte[] serialize(Map<String, Object> claims) {
        try {
            return objectMapper.writeValueAsBytes(claims);
        } catch (Exception ex) {
            // The claims map is plain JSON-serialisable values; this should not happen.
            throw new IllegalStateException("failed to serialise confirmation claims", ex);
        }
    }

    private byte[] sign(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGO));
            return mac.doFinal(data);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to sign confirmation token", ex);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        return new BigDecimal(String.valueOf(value));
    }
}
