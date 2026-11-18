package apigw.model;

import java.util.List;

/**
 * Gateway-wide request handling settings.
 *
 * @param trustedProxies      CIDRs whose {@code X-Forwarded-*} / {@code Forwarded} headers are kept and appended to;
 *                            from any other peer those headers are replaced
 * @param stripRequestHeaders inbound headers always removed before plugins run (client-supplied identity)
 * @param viaName             pseudonym for the {@code Via} header; empty disables it
 * @param defaultTimeouts     timeouts applied where neither route nor service sets one
 * @param maxRequestBodyBytes requests declaring a larger {@code Content-Length} are rejected with 413
 */
public record Settings(
        List<String> trustedProxies,
        List<String> stripRequestHeaders,
        String viaName,
        Timeouts defaultTimeouts,
        long maxRequestBodyBytes) {

    /** Identity headers that only the gateway may set. Clients sending them get them stripped. */
    public static final List<String> DEFAULT_STRIPPED = List.of(
            "x-consumer-id", "x-consumer-username", "x-tenant-id", "x-authenticated-user",
            "x-authenticated-scope", "x-credential-identifier", "x-gateway-identity");

    public static final Settings DEFAULTS = new Settings(List.of(), DEFAULT_STRIPPED, "apigw",
            Timeouts.DEFAULTS, 10L * 1024 * 1024);

    public Settings {
        trustedProxies = List.copyOf(trustedProxies);
        stripRequestHeaders = List.copyOf(stripRequestHeaders);
    }
}
