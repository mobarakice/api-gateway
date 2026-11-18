package apigw.core.snapshot;

import apigw.model.Cidr;
import io.vertx.core.http.HttpVersion;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gateway-wide settings in request-path form.
 *
 * @param viaName pseudonym for the Via header; empty = no Via
 */
public record CompiledSettings(List<Cidr> trustedProxies, Set<String> strippedHeaders, String viaName,
        long maxRequestBodyBytes, Set<String> dropInbound, String via10, String via11, String via20) {

    /** Inbound headers the pipeline always recomputes, so the client's values never pass through as-is. */
    static final Set<String> RECOMPUTED = Set.of("host", "x-forwarded-for", "x-forwarded-proto", "x-forwarded-host",
            "x-forwarded-port", "forwarded", "x-request-id");

    public CompiledSettings {
        trustedProxies = List.copyOf(trustedProxies);
        strippedHeaders = Set.copyOf(strippedHeaders);
        dropInbound = Set.copyOf(dropInbound);
    }

    public static CompiledSettings of(List<Cidr> trustedProxies, Set<String> stripped, String viaName,
            long maxRequestBodyBytes) {
        Set<String> drop = new HashSet<>(stripped);
        drop.addAll(RECOMPUTED);
        String v = viaName.isEmpty() ? "" : " " + viaName;
        return new CompiledSettings(trustedProxies, stripped, viaName, maxRequestBodyBytes, drop,
                v.isEmpty() ? "" : "1.0" + v, v.isEmpty() ? "" : "1.1" + v, v.isEmpty() ? "" : "2.0" + v);
    }

    /** True if {@code address} (4 or 16 bytes) belongs to a trusted proxy. */
    public boolean trusted(byte[] address) {
        for (Cidr c : trustedProxies) {
            if (c.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /** Via entry for the protocol version the message was received with; empty when disabled. */
    public String via(HttpVersion version) {
        return switch (version) {
            case HTTP_1_0 -> via10;
            case HTTP_2 -> via20;
            default -> via11;
        };
    }
}
