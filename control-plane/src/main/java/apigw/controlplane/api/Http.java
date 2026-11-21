package apigw.controlplane.api;

import apigw.config.etcd.EtcdException;
import apigw.controlplane.Json;
import apigw.model.Cidr;
import apigw.model.ConfigError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Response, body and client-address helpers for the Admin API. */
final class Http {

    private static final Logger LOG = LoggerFactory.getLogger(Http.class);
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private Http() {
    }

    /** A JSON response; {@code etag} may be null. */
    record Response(int status, JsonNode body, String etag) {
        static Response ok(JsonNode body) {
            return new Response(200, body, null);
        }
    }

    static void send(HttpServerRequest req, String requestId, Response r) {
        HttpServerResponse resp = base(req, requestId).setStatusCode(r.status());
        if (r.etag() != null) {
            resp.putHeader("etag", r.etag());
        }
        if (r.body() == null) {
            resp.end();
            return;
        }
        resp.putHeader("content-type", "application/json").end(Buffer.buffer(Json.bytes(r.body())));
    }

    static void problem(HttpServerRequest req, String requestId, Throwable error) {
        ApiException e = asApi(error, requestId);
        ObjectNode n = Json.MAPPER.createObjectNode().put("type", "about:blank").put("title", e.title())
                .put("status", e.status());
        if (e.getMessage() != null) {
            n.put("detail", e.getMessage());
        }
        n.put("instance", req.path()).put("requestId", requestId);
        if (!e.errors().isEmpty()) {
            ArrayNode a = n.putArray("errors");
            for (ConfigError ce : e.errors()) {
                a.addObject().put("path", ce.path()).put("message", ce.message());
            }
        }
        if (!e.conflicts().isEmpty()) {
            ArrayNode a = n.putArray("conflicts");
            for (ApiException.Conflict c : e.conflicts()) {
                a.addObject().put("key", c.key()).put("expectedRevision", c.expectedRevision())
                        .put("actualRevision", c.actualRevision());
            }
        }
        HttpServerResponse resp = base(req, requestId).setStatusCode(e.status())
                .putHeader("content-type", "application/problem+json");
        if (e.status() == 401) {
            resp.putHeader("www-authenticate", "Bearer realm=\"apigw-admin\"");
        }
        if (e.status() == 503) {
            resp.putHeader("retry-after", "1");
        }
        resp.end(Buffer.buffer(Json.bytes(n)));
    }

    private static ApiException asApi(Throwable error, String requestId) {
        if (error instanceof ApiException a) {
            return a;
        }
        if (error instanceof EtcdException) {
            LOG.warn("etcd request failed requestId={} cause={}", requestId, error.getMessage());
            return new ApiException(503, "Service unavailable", "the configuration store is unavailable; retry");
        }
        if (error instanceof IllegalArgumentException iae && iae.getMessage() != null
                && iae.getMessage().startsWith("malformed JSON")) {
            return ApiException.badRequest("body is not valid JSON");
        }
        LOG.error("admin request failed requestId={} error={}", requestId, error.toString());
        return new ApiException(500, "Internal error", "unexpected error; quote the request ID when reporting it");
    }

    private static HttpServerResponse base(HttpServerRequest req, String requestId) {
        return req.response().putHeader("x-request-id", requestId).putHeader("cache-control", "no-store")
                .putHeader("x-content-type-options", "nosniff")
                .putHeader("content-security-policy", "default-src 'none'; frame-ancestors 'none'");
    }

    /** Reads a JSON body of at most {@link #MAX_BODY_BYTES}. */
    static Future<JsonNode> json(HttpServerRequest req) {
        String cl = req.getHeader("content-length");
        if (cl != null) {
            try {
                if (Long.parseLong(cl.strip()) > MAX_BODY_BYTES) {
                    return Future.failedFuture(new ApiException(413, "Content too large",
                            "request bodies are limited to " + MAX_BODY_BYTES + " bytes"));
                }
            } catch (NumberFormatException e) {
                return Future.failedFuture(ApiException.badRequest("invalid Content-Length"));
            }
        }
        Promise<JsonNode> p = Promise.promise();
        Buffer body = Buffer.buffer();
        req.handler(chunk -> {
            if (p.future().isComplete()) {
                return;
            }
            if (body.length() + chunk.length() > MAX_BODY_BYTES) {
                p.tryFail(new ApiException(413, "Content too large", "request bodies are limited to "
                        + MAX_BODY_BYTES + " bytes"));
                return;
            }
            body.appendBuffer(chunk);
        });
        req.exceptionHandler(p::tryFail);
        req.endHandler(v -> {
            if (body.length() == 0) {
                p.tryFail(ApiException.badRequest("a JSON body is required"));
                return;
            }
            try {
                p.tryComplete(Json.parse(body.getBytes()));
            } catch (IllegalArgumentException e) {
                p.tryFail(ApiException.badRequest("body is not valid JSON"));
            }
        });
        // The request was paused until authentication finished; handlers are set, start reading.
        req.resume();
        return p.future();
    }

    /** The caller's IP: the peer, or the right-most untrusted X-Forwarded-For entry behind trusted proxies. */
    static String clientIp(HttpServerRequest req, List<Cidr> trustedProxies) {
        String peer = req.remoteAddress() == null ? "unknown" : req.remoteAddress().hostAddress();
        if (trustedProxies.isEmpty() || !trusted(peer, trustedProxies)) {
            return peer;
        }
        String xff = req.getHeader("x-forwarded-for");
        if (xff == null) {
            return peer;
        }
        String[] hops = xff.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].strip();
            if (!trusted(hop, trustedProxies)) {
                return literal(hop) == null ? peer : hop;
            }
        }
        return peer;
    }

    private static byte[] literal(String ip) {
        try {
            return Cidr.literal(ip);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean trusted(String ip, List<Cidr> cidrs) {
        byte[] a = literal(ip);
        if (a == null) {
            return false;
        }
        for (Cidr c : cidrs) {
            if (c.contains(a)) {
                return true;
            }
        }
        return false;
    }
}
