package apigw.core.pipeline;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpHeaders;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Hop-by-hop handling (RFC 9110 §7.6.1) shared by request and response paths. */
public final class Headers {

    /** Always connection-specific; never forwarded in either direction. */
    static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-connection", "te", "trailer",
            "transfer-encoding", "upgrade", "proxy-authenticate", "proxy-authorization", "http2-settings");

    public static final CharSequence VIA = HttpHeaders.createOptimized("via");

    private Headers() {
    }

    /** Copies {@code from} into a new map, dropping hop-by-hop headers, those named in Connection, and {@code drop}. */
    public static MultiMap forwardable(MultiMap from, Set<String> drop) {
        List<String> connection = from.getAll("connection");
        MultiMap out = HttpHeaders.headers();
        for (var e : from) {
            String name = e.getKey().toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(name) || drop.contains(name) || listed(connection, name)) {
                continue;
            }
            out.add(name, e.getValue());
        }
        return out;
    }

    private static boolean listed(List<String> connection, String name) {
        for (String value : connection) {
            int start = 0;
            while (start <= value.length()) {
                int comma = value.indexOf(',', start);
                int end = comma < 0 ? value.length() : comma;
                String token = value.substring(start, end).trim();
                if (token.equalsIgnoreCase(name)) {
                    return true;
                }
                if (comma < 0) {
                    break;
                }
                start = comma + 1;
            }
        }
        return false;
    }

    /** Appends to Via (comma-joined), or sets it. */
    public static void appendVia(MultiMap h, String via) {
        if (via.isEmpty()) {
            return;
        }
        String existing = h.get(VIA);
        h.set(VIA, existing == null ? via : existing + ", " + via);
    }
}
