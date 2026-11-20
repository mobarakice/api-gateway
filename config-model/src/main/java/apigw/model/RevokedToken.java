package apigw.model;

import java.time.Instant;

/**
 * A revoked token ID ({@code policies/revoked-tokens/{id}}). The JWT plugin rejects tokens whose
 * {@code jti} (and {@code iss}, when set here) match until {@code expiresAt}, which should be the
 * token's own expiry; the control plane also attaches the key to a lease so it disappears afterwards.
 *
 * @param issuer null = any issuer
 */
public record RevokedToken(String id, String jti, String issuer, Instant expiresAt) {
}
