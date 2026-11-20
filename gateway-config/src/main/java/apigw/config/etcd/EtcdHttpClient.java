package apigw.config.etcd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.parsetools.RecordParser;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fully asynchronous etcd v3 client over etcd's HTTP/JSON (grpc-gateway) API, built on the Vert.x
 * HTTP client so it adds no second networking stack to the gateway. See ADR 0009.
 *
 * <p>Threading: callbacks run on the Vert.x context of the caller. Large range reads are parsed in
 * the callback, so the config worker calls this from a worker context, never a traffic event loop.
 *
 * <p>Auth: with a username configured, the client obtains a token via {@code /v3/auth/authenticate}
 * before the first call and re-authenticates once when etcd reports the token invalid or expired.
 *
 * <p>Endpoints: requests go to the current endpoint; a transport failure moves to the next one.
 */
public final class EtcdHttpClient implements AutoCloseable {

    private static final String AUTHORIZATION = "authorization";

    private final HttpClientAgent http;
    private final EtcdClientConfig config;
    private final List<URI> endpoints;
    private final AtomicInteger current = new AtomicInteger();
    private volatile String token;

    private EtcdHttpClient(HttpClientAgent http, EtcdClientConfig config) {
        this.http = http;
        this.config = config;
        this.endpoints = config.endpoints();
    }

    public static EtcdHttpClient create(Vertx vertx, EtcdClientConfig config) {
        HttpClientOptions options = new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_1_1)
                .setConnectTimeout((int) Math.min(Integer.MAX_VALUE, config.requestTimeout().toMillis()))
                .setKeepAlive(true)
                .setVerifyHost(true);
        if (config.endpoints().getFirst().getScheme().equals("https")) {
            options.setSsl(true);
            config.caFile().ifPresent(ca -> options.setTrustOptions(new PemTrustOptions().addCertPath(ca.toString())));
            config.clientCert().ifPresent(cert -> options.setKeyCertOptions(new PemKeyCertOptions()
                    .setCertPath(cert.toString())
                    .setKeyPath(config.clientKey().orElseThrow().toString())));
        }
        return new EtcdHttpClient(vertx.createHttpClient(options, new PoolOptions().setHttp1MaxSize(8)), config);
    }

    /** Endpoint the next request goes to. */
    public URI currentEndpoint() {
        return endpoints.get(Math.floorMod(current.get(), endpoints.size()));
    }

    /** Obtains a fresh auth token. A no-op success when no username is configured. */
    public Future<Void> authenticate() {
        if (config.username().isEmpty()) {
            return Future.succeededFuture();
        }
        ObjectNode body = EtcdJson.MAPPER.createObjectNode()
                .put("name", config.username().get())
                .put("password", config.password().orElseThrow().reveal());
        return send("/v3/auth/authenticate", body, null).map(n -> {
            String t = n.path("token").asText("");
            if (t.isEmpty()) {
                throw new EtcdException(EtcdException.UNAUTHENTICATED, "etcd returned no auth token");
            }
            token = t;
            return null;
        });
    }

    /** Reads one key at {@code revision} (0 = latest). */
    public Future<RangeResult> get(String key, long revision) {
        return call("/v3/kv/range", EtcdJson.rangeRequest(key, null, revision, 0, false)).map(EtcdJson::range);
    }

    /**
     * Reads keys starting with {@code prefix} at one revision. Use {@code fromKey} to page:
     * pass the prefix for the first page, then the last returned key + {@code "\0"}.
     *
     * @param revision 0 = latest; otherwise every page must use the first page's revision
     * @param limit    0 = no limit
     */
    public Future<RangeResult> rangePrefix(String prefix, String fromKey, long revision, long limit) {
        return call("/v3/kv/range", EtcdJson.rangeRequest(fromKey, EtcdJson.prefixEnd(prefix), revision, limit,
                false)).map(EtcdJson::range);
    }

    /** Count of keys under {@code prefix} and the current store revision, without transferring values. */
    public Future<RangeResult> countPrefix(String prefix) {
        return call("/v3/kv/range", EtcdJson.rangeRequest(prefix, EtcdJson.prefixEnd(prefix), 0, 0, true))
                .map(EtcdJson::range);
    }

    /** Writes a key, optionally attached to a lease. Returns the new store revision. */
    public Future<Long> put(String key, byte[] value, long lease) {
        return call("/v3/kv/put", EtcdJson.putRequest(key, value, lease)).map(EtcdJson::revision);
    }

    /** Grants a lease with the given time-to-live. */
    public Future<Lease> leaseGrant(long ttlSeconds) {
        ObjectNode body = EtcdJson.MAPPER.createObjectNode().put("TTL", Long.toString(ttlSeconds));
        return call("/v3/lease/grant", body).map(n -> new Lease(EtcdJson.longValue(n, "ID"),
                EtcdJson.longValue(n, "TTL")));
    }

    /**
     * Renews a lease once. The HTTP gateway answers a single-message keepalive with one result and ends.
     *
     * @return remaining TTL in seconds; 0 when the lease no longer exists (expired or revoked)
     */
    public Future<Long> leaseKeepAlive(long leaseId) {
        ObjectNode body = EtcdJson.MAPPER.createObjectNode().put("ID", Long.toString(leaseId));
        return call("/v3/lease/keepalive", body).map(n -> EtcdJson.longValue(n.path("result"), "TTL"));
    }

    /** Revokes a lease; keys attached to it are deleted. */
    public Future<Void> leaseRevoke(long leaseId) {
        ObjectNode body = EtcdJson.MAPPER.createObjectNode().put("ID", Long.toString(leaseId));
        return call("/v3/lease/revoke", body).mapEmpty();
    }

    /** A granted lease. */
    public record Lease(long id, long ttlSeconds) {
    }

    /**
     * Checks whether the authenticated user may write {@code key} without ever writing it: sends a
     * transaction whose compare ({@code version < 0}) can never be true, with a put in the success
     * branch. etcd checks permissions for every operation in a transaction before evaluating it,
     * so a read-only user gets PERMISSION_DENIED and a writer gets {@code succeeded=false}.
     *
     * @return true if the user is allowed to write the key
     */
    public Future<Boolean> canWrite(String key) {
        ObjectNode txn = EtcdJson.MAPPER.createObjectNode();
        ArrayNode compare = txn.putArray("compare");
        compare.addObject().put("key", EtcdJson.b64(key)).put("target", "VERSION").put("result", "LESS")
                .put("version", "0");
        txn.putArray("success").addObject().set("request_put", EtcdJson.putRequest(key, new byte[0], 0));
        return call("/v3/kv/txn", txn)
                .map(n -> {
                    if (n.path("succeeded").asBoolean(false)) {
                        throw new EtcdException(EtcdException.TRANSPORT, "write probe compare unexpectedly succeeded");
                    }
                    return Boolean.TRUE;
                })
                .recover(e -> e instanceof EtcdException ee && ee.permissionDenied()
                        ? Future.succeededFuture(Boolean.FALSE) : Future.failedFuture(e));
    }

    /**
     * Opens a watch on every key under {@code prefix} starting at {@code startRevision}.
     * {@code onResponse} gets each message, including the creation ack and progress notifications.
     * {@code onEnd} is called exactly once when the stream ends for any reason, including
     * {@link Watch#cancel()}; the caller decides whether to resume or resync.
     */
    public Watch watchPrefix(String prefix, long startRevision, boolean progressNotify,
            Handler<WatchResponse> onResponse, Handler<Throwable> onEnd) {
        Watch watch = new Watch(onEnd);
        ensureToken().compose(v -> {
            URI endpoint = currentEndpoint();
            watch.endpoint = endpoint;
            RequestOptions opts = new RequestOptions()
                    .setMethod(HttpMethod.POST)
                    .setAbsoluteURI(endpoint.resolve("/v3/watch").toString())
                    .setConnectTimeout(config.requestTimeout().toMillis())
                    .putHeader("content-type", "application/json");
            String t = token;
            if (t != null) {
                opts.putHeader(AUTHORIZATION, t);
            }
            return http.request(opts).onFailure(e -> rotate(endpoint));
        }).compose(req -> {
            watch.attach(req);
            return req.send(EtcdJson.write(EtcdJson.watchCreate(prefix, EtcdJson.prefixEnd(prefix),
                    startRevision, progressNotify)));
        }).onSuccess(resp -> {
            if (resp.statusCode() != 200) {
                resp.body().onComplete(b -> watch.end(httpError(resp.statusCode(), b.succeeded() ? b.result() : null)));
                return;
            }
            RecordParser frames = RecordParser.newDelimited("\n", frame -> {
                if (frame.length() == 0) {
                    return;
                }
                JsonNode n = EtcdJson.parse(frame.getBytes());
                EtcdException err = EtcdJson.streamError(n);
                if (err != null) {
                    if (err.invalidToken()) {
                        token = null;
                    }
                    watch.end(err);
                    return;
                }
                WatchResponse r = EtcdJson.watch(n.path("result"));
                onResponse.handle(r);
                if (r.canceled()) {
                    watch.end(new EtcdException(r.compactRevision() > 0 ? EtcdException.OUT_OF_RANGE
                            : EtcdException.TRANSPORT, "watch canceled: " + r.cancelReason()));
                }
            });
            frames.exceptionHandler(watch::end);
            resp.handler(frames);
            resp.exceptionHandler(watch::end);
            resp.endHandler(v -> watch.end(new EtcdException(EtcdException.UNAVAILABLE, "watch stream ended")));
        }).onFailure(watch::end);
        return watch;
    }

    @Override
    public void close() {
        http.close();
    }

    private Future<JsonNode> call(String path, ObjectNode body) {
        return ensureToken()
                .compose(v -> send(path, body, token))
                .recover(e -> {
                    if (e instanceof EtcdException ee && ee.invalidToken() && config.username().isPresent()) {
                        token = null;
                        return authenticate().compose(v -> send(path, body, token));
                    }
                    return Future.failedFuture(e);
                });
    }

    private Future<Void> ensureToken() {
        return token != null || config.username().isEmpty() ? Future.succeededFuture() : authenticate();
    }

    private Future<JsonNode> send(String path, ObjectNode body, String authToken) {
        URI endpoint = currentEndpoint();
        RequestOptions opts = new RequestOptions()
                .setMethod(HttpMethod.POST)
                .setAbsoluteURI(endpoint.resolve(path).toString())
                .setTimeout(config.requestTimeout().toMillis())
                .putHeader("content-type", "application/json");
        if (authToken != null) {
            opts.putHeader(AUTHORIZATION, authToken);
        }
        return http.request(opts)
                .compose(req -> req.send(EtcdJson.write(body)))
                .compose(resp -> resp.body().map(buf -> {
                    JsonNode n = buf.length() == 0
                            ? EtcdJson.MAPPER.createObjectNode() : EtcdJson.parse(buf.getBytes());
                    EtcdException err = EtcdJson.unaryError(n);
                    if (err != null) {
                        throw err;
                    }
                    if (resp.statusCode() != 200) {
                        throw httpError(resp.statusCode(), buf);
                    }
                    return n;
                }))
                .recover(e -> {
                    if (e instanceof EtcdException) {
                        return Future.failedFuture(e);
                    }
                    rotate(endpoint);
                    return Future.failedFuture(new EtcdException("etcd request to " + endpoint + " failed: "
                            + e.getMessage(), e));
                });
    }

    private void rotate(URI failed) {
        int i = current.get();
        if (endpoints.get(Math.floorMod(i, endpoints.size())).equals(failed)) {
            current.compareAndSet(i, i + 1);
        }
    }

    private static EtcdException httpError(int status, Buffer body) {
        if (body != null && body.length() > 0) {
            try {
                JsonNode n = EtcdJson.parse(body.getBytes());
                EtcdException err = EtcdJson.unaryError(n);
                if (err == null) {
                    err = EtcdJson.streamError(n);
                }
                if (err != null) {
                    return err;
                }
            } catch (EtcdException ignored) {
                // Not JSON: fall through to a status-only error.
            }
        }
        return new EtcdException(EtcdException.TRANSPORT, "etcd HTTP status " + status);
    }

    /** Handle for an open watch stream. */
    public static final class Watch {

        private final Handler<Throwable> onEnd;
        private final AtomicBoolean ended = new AtomicBoolean();
        private final Promise<Void> endPromise = Promise.promise();
        private volatile HttpClientRequest request;
        private volatile URI endpoint;

        private Watch(Handler<Throwable> onEnd) {
            this.onEnd = onEnd;
        }

        /** The member this stream is (being) opened on; null until the request is issued. */
        public URI endpoint() {
            return endpoint;
        }

        private void attach(HttpClientRequest req) {
            this.request = req;
            if (ended.get()) {
                req.reset();
            }
        }

        private void end(Throwable cause) {
            if (ended.compareAndSet(false, true)) {
                HttpClientRequest r = request;
                if (r != null) {
                    r.reset();
                }
                endPromise.complete();
                onEnd.handle(cause);
            }
        }

        /** Closes the stream. {@code onEnd} is called with a cancellation exception. */
        public void cancel() {
            end(new EtcdException(EtcdException.TRANSPORT, "watch canceled by client"));
        }

        /** Completes when the stream has ended. */
        public Future<Void> ended() {
            return endPromise.future();
        }
    }
}
