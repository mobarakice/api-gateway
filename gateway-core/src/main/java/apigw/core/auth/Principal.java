package apigw.core.auth;

import java.util.Set;

/**
 * Who made the request, established by an authentication plugin through
 * {@link apigw.core.plugin.Exchange#authenticate(Principal)}. Never derived from client-supplied
 * identity headers (those are stripped before plugins run).
 *
 * @param subject      token {@code sub} or the consumer ID
 * @param consumer     the matched consumer, or null (e.g. a token without a consumer mapping)
 * @param tenantId     tenant of the principal; null = none
 * @param roles        role names
 * @param attributes   consumer attributes or token claims
 * @param method       how it authenticated, e.g. {@code jwt}, {@code key-auth}
 * @param credentialId the credential used (API key credential ID or token issuer); never a secret
 */
public record Principal(String subject, CompiledConsumer consumer, String tenantId, Set<String> roles,
        Attributes attributes, String method, String credentialId) {

    public Principal {
        roles = Set.copyOf(roles);
    }

    public static Principal of(CompiledConsumer c, String method, String credentialId) {
        return new Principal(c.id(), c, c.tenantId(), c.roles(), c.attributes(), method, credentialId);
    }
}
