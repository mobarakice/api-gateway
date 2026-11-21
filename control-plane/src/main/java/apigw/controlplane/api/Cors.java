package apigw.controlplane.api;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import java.net.URI;
import java.util.Set;

/**
 * CORS for the admin console when it is served from its own origin (spec §10). Only exact,
 * configured origins are allowed (no wildcards, no credentials: the console sends bearer tokens,
 * never cookies). Requests from other origins get no CORS headers, so browsers block them; the API
 * still authenticates every request.
 */
public final class Cors implements Handler<HttpServerRequest> {

    static final String ALLOW_METHODS = "GET, POST, PUT, DELETE";
    static final String ALLOW_HEADERS = "authorization, content-type, if-match";
    static final String EXPOSE_HEADERS = "etag, location, retry-after, x-request-id";

    private final Set<String> origins;
    private final Handler<HttpServerRequest> next;

    public Cors(Set<String> origins, Handler<HttpServerRequest> next) {
        for (String o : origins) {
            requireOrigin(o);
        }
        this.origins = Set.copyOf(origins);
        this.next = next;
    }

    /** Accepts {@code scheme://host[:port]} with scheme http or https and nothing else. */
    public static String requireOrigin(String origin) {
        URI u;
        try {
            u = URI.create(origin);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid CORS origin: " + origin, e);
        }
        boolean ok = ("https".equals(u.getScheme()) || "http".equals(u.getScheme())) && u.getHost() != null
                && u.getRawUserInfo() == null && (u.getRawPath() == null || u.getRawPath().isEmpty())
                && u.getRawQuery() == null && u.getRawFragment() == null;
        if (!ok || !origin.equals(u.getScheme() + "://" + u.getRawAuthority())) {
            throw new IllegalArgumentException("CORS origin must be scheme://host[:port]: " + origin);
        }
        return origin;
    }

    @Override
    public void handle(HttpServerRequest req) {
        String origin = req.getHeader("origin");
        boolean allowed = origin != null && origins.contains(origin);
        boolean preflight = req.method() == HttpMethod.OPTIONS
                && req.getHeader("access-control-request-method") != null;
        if (preflight) {
            var resp = req.response().putHeader("vary", "origin").putHeader("cache-control", "no-store");
            if (!allowed) {
                resp.setStatusCode(403).end();
                return;
            }
            resp.setStatusCode(204).putHeader("access-control-allow-origin", origin)
                    .putHeader("access-control-allow-methods", ALLOW_METHODS)
                    .putHeader("access-control-allow-headers", ALLOW_HEADERS)
                    .putHeader("access-control-max-age", "600").end();
            return;
        }
        req.response().putHeader("vary", "origin");
        if (allowed) {
            req.response().putHeader("access-control-allow-origin", origin)
                    .putHeader("access-control-expose-headers", EXPOSE_HEADERS);
        }
        next.handle(req);
    }
}
