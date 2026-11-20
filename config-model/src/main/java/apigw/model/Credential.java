package apigw.model;

/**
 * A consumer's credential. Only API keys exist so far. The presented key is
 * {@code <keyId>.<secret>}; {@code keyId} is public and globally unique (it selects the credential
 * without scanning), {@code hash} is {@code sha256$<salt>$<digest>} (base64url, no padding) of the
 * secret, see {@link ApiKeyHash}. The secret itself is never stored.
 *
 * @param tenantId must be null or equal to the consumer's tenant
 */
public record Credential(String id, String consumerId, String tenantId, Type type, String keyId, String hash) {

    /** Credential types. */
    public enum Type {
        API_KEY
    }
}
