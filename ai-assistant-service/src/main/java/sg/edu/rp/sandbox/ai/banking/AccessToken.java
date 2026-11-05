package sg.edu.rp.sandbox.ai.banking;

/**
 * The signed-in user's raw OIDC access token (JWT), captured from the inbound
 * request and reused on-behalf-of for outbound gateway calls.
 *
 * <p>This is a value wrapper so the token is passed explicitly through the call
 * chain. Its value is a secret: it is never logged and never placed in a response.
 * {@link #toString()} is redacted on purpose.
 */
public final class AccessToken {

    private final String value;

    private AccessToken(String value) {
        this.value = value;
    }

    public static AccessToken of(String rawJwt) {
        if (rawJwt == null || rawJwt.isBlank()) {
            throw new IllegalArgumentException("access token must not be blank");
        }
        return new AccessToken(rawJwt);
    }

    /** @return the raw JWT string (for the Authorization header only). */
    public String value() {
        return value;
    }

    /** Redacted so the token never leaks into logs via string interpolation. */
    @Override
    public String toString() {
        return "AccessToken[REDACTED]";
    }
}
