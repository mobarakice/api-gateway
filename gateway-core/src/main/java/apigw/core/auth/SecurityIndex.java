package apigw.core.auth;

import java.time.Instant;
import java.util.Map;

/**
 * Per-snapshot lookup tables for authentication plugins. Immutable; built at compile time.
 *
 * @param consumers by consumer ID
 * @param apiKeys   by public key ID
 * @param revoked   revoked token IDs: {@code jti} to (issuer or null for any) and expiry
 */
public record SecurityIndex(Map<String, CompiledConsumer> consumers, Map<String, ApiKey> apiKeys,
        Map<String, Revocation> revoked) {

    public static final SecurityIndex EMPTY = new SecurityIndex(Map.of(), Map.of(), Map.of());

    public SecurityIndex {
        consumers = Map.copyOf(consumers);
        apiKeys = Map.copyOf(apiKeys);
        revoked = Map.copyOf(revoked);
    }

    /** An API key credential: salted hash only, never the secret. */
    public record ApiKey(String credentialId, CompiledConsumer consumer, byte[] salt, byte[] digest) {
    }

    /** One revocation entry. */
    public record Revocation(String issuer, Instant expiresAt) {
    }

    /** True if a token with this {@code jti} from {@code issuer} is revoked at {@code now}. */
    public boolean isRevoked(String jti, String issuer, Instant now) {
        Revocation r = revoked.get(jti);
        return r != null && (r.issuer() == null || r.issuer().equals(issuer))
                && (r.expiresAt() == null || now.isBefore(r.expiresAt()));
    }
}
