package apigw.core.pipeline;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import java.util.List;

/**
 * Rejects requests that intermediaries could interpret differently (request smuggling, path
 * confusion). Policy (ADR 0013): the gateway never decodes or normalises a path; it refuses
 * ambiguous ones, so the path it routes on is byte-for-byte the path it forwards.
 */
public final class RequestValidator {

    /** Result: 0 = valid, otherwise the status to reject with. */
    private RequestValidator() {
    }

    /**
     * @return 0 when acceptable, else 400, 405, 413 or 501
     */
    public static int check(HttpServerRequest req, long maxBodyBytes) {
        HttpMethod m = req.method();
        if (m == HttpMethod.CONNECT) {
            return 501;
        }
        String path = req.path();
        if (path == null || !validPath(path)) {
            return 400;
        }
        MultiMap h = req.headers();
        List<String> cl = h.getAll("content-length");
        if (req.version() != HttpVersion.HTTP_2) {
            List<String> te = h.getAll("transfer-encoding");
            if (!te.isEmpty()) {
                if (!cl.isEmpty() || te.size() > 1 || !"chunked".equalsIgnoreCase(te.getFirst().trim())) {
                    return 400;
                }
                if (req.version() == HttpVersion.HTTP_1_0) {
                    return 400;
                }
            }
            if (h.getAll("host").size() > 1) {
                return 400;
            }
            if (req.version() == HttpVersion.HTTP_1_1 && !h.contains("host")) {
                return 400;
            }
        }
        if (cl.size() > 1) {
            return 400;
        }
        if (cl.size() == 1) {
            long len = parseLength(cl.getFirst());
            if (len < 0) {
                return 400;
            }
            if (len > maxBodyBytes) {
                return 413;
            }
        }
        return 0;
    }

    /** Non-negative decimal without sign or whitespace, or -1. */
    static long parseLength(String v) {
        if (v.isEmpty() || v.length() > 18) {
            return -1;
        }
        long n = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            n = n * 10 + (c - '0');
        }
        return n;
    }

    /**
     * Origin-form path: starts with '/', printable ASCII only, no empty segment except a trailing
     * one, no dot segments, no backslash, and no percent-encoded '/', '\', '.' or control bytes.
     */
    static boolean validPath(String p) {
        int len = p.length();
        if (len == 0 || p.charAt(0) != '/') {
            return false;
        }
        int segStart = 1;
        for (int i = 1; i <= len; i++) {
            char c = i < len ? p.charAt(i) : '/';
            if (c == '/') {
                int segLen = i - segStart;
                if (segLen == 0 && i < len) {
                    return false;
                }
                if (segLen == 1 && p.charAt(segStart) == '.') {
                    return false;
                }
                if (segLen == 2 && p.charAt(segStart) == '.' && p.charAt(segStart + 1) == '.') {
                    return false;
                }
                segStart = i + 1;
                continue;
            }
            if (c <= 0x20 || c >= 0x7f || c == '\\' || c == '#') {
                return false;
            }
            if (c == '%') {
                if (i + 2 >= len) {
                    return false;
                }
                int hi = hex(p.charAt(i + 1));
                int lo = hex(p.charAt(i + 2));
                if (hi < 0 || lo < 0) {
                    return false;
                }
                int b = hi * 16 + lo;
                if (b < 0x20 || b == 0x7f || b == '/' || b == '\\' || b == '.') {
                    return false;
                }
            }
        }
        return true;
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
