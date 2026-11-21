package apigw.proxy;

import apigw.core.pipeline.Pipeline;
import apigw.core.plugin.PluginRegistry;
import apigw.core.snapshot.Snapshot;
import apigw.core.snapshot.SnapshotCompiler;
import apigw.core.snapshot.SnapshotHolder;
import apigw.model.ConfigDecoder;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real servers on loopback: echo upstreams and a gateway front end (pipeline + forwarder deployed
 * on an event loop exactly as gateway-app does). No mocks.
 */
final class ProxyHarness {

    final Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2));
    final SnapshotHolder holder = new SnapshotHolder();
    final List<Echo> upstreams = new ArrayList<>();
    int gatewayPort;
    /** DNS for the gateway's upstream resolution; null = the platform resolver. */
    TargetResolver.Lookup lookup;
    /** Active health checker shared by the gateway's event loops; created by {@link #startGateway}. */
    HealthChecker health;
    private final Map<String, AtomicInteger> flaky = new java.util.concurrent.ConcurrentHashMap<>();

    /** An echo upstream. Paths: /status/{code}, .../slow/{ms}, /bytes/{n}, anything else echoes. */
    static final class Echo {
        final int port;
        final String name;
        final AtomicInteger hits = new AtomicInteger();
        final HttpServer server;

        Echo(String name, HttpServer server) {
            this.name = name;
            this.server = server;
            this.port = server.actualPort();
        }
    }

    Echo upstream(String name, boolean h2c) throws Exception {
        HttpServerOptions o = new HttpServerOptions().setHost("127.0.0.1").setPort(0).setHttp2ClearTextEnabled(h2c);
        AtomicInteger[] hits = new AtomicInteger[1];
        HttpServer server = vertx.createHttpServer(o).requestHandler(req -> {
            hits[0].incrementAndGet();
            handleEcho(name, req);
        });
        await(server.listen());
        Echo e = new Echo(name, server);
        hits[0] = e.hits;
        upstreams.add(e);
        return e;
    }

    private void handleEcho(String name, HttpServerRequest req) {
        String path = req.path();
        if (path.startsWith("/flaky/")) {
            // /flaky/{n}/{key}: 503 for the first n hits of this exact path, then echo.
            int n = Integer.parseInt(path.split("/")[2]);
            if (flaky.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet() <= n) {
                req.response().setStatusCode(503).end();
                return;
            }
        }
        if (path.startsWith("/status/")) {
            req.response().setStatusCode(Integer.parseInt(path.substring(8))).end();
            return;
        }
        int slow = path.indexOf("/slow/");
        if (slow >= 0) {
            vertx.setTimer(Long.parseLong(path.substring(slow + 6)), id -> req.response().end("slow"));
            return;
        }
        if (path.startsWith("/bytes/")) {
            int n = Integer.parseInt(path.substring(7));
            req.response().setChunked(true);
            byte[] chunk = new byte[16 * 1024];
            int sent = 0;
            while (sent < n) {
                int len = Math.min(chunk.length, n - sent);
                req.response().write(Buffer.buffer(len == chunk.length ? chunk : java.util.Arrays.copyOf(chunk, len)));
                sent += len;
            }
            req.response().end();
            return;
        }
        long[] count = new long[1];
        req.handler(b -> count[0] += b.length());
        req.endHandler(v -> {
            JsonObject headers = new JsonObject();
            req.headers().forEach(h -> headers.put(h.getKey().toLowerCase(java.util.Locale.ROOT),
                    headers.containsKey(h.getKey().toLowerCase(java.util.Locale.ROOT))
                            ? headers.getString(h.getKey().toLowerCase(java.util.Locale.ROOT)) + "|" + h.getValue()
                            : h.getValue()));
            JsonObject out = new JsonObject().put("upstream", name).put("method", req.method().name())
                    .put("uri", req.uri()).put("version", req.version().name()).put("bodyBytes", count[0])
                    .put("authority", req.authority() == null ? null : req.authority().toString())
                    .put("headers", headers);
            req.response().putHeader("content-type", "application/json");
            if (req.version() != io.vertx.core.http.HttpVersion.HTTP_2) {
                // Connection-specific headers are illegal in HTTP/2 responses.
                req.response().putHeader("connection", "x-hop").putHeader("x-hop", "must-not-leak")
                        .putHeader("keep-alive", "timeout=5");
            }
            req.response().end(out.encode());
        });
    }

    /** An upstream with its own behaviour; counts hits. */
    Echo custom(String name, io.vertx.core.Handler<HttpServerRequest> handler) throws Exception {
        AtomicInteger[] hits = new AtomicInteger[1];
        HttpServer server = vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0))
                .requestHandler(req -> {
                    hits[0].incrementAndGet();
                    handler.handle(req);
                });
        await(server.listen());
        Echo e = new Echo(name, server);
        hits[0] = e.hits;
        upstreams.add(e);
        return e;
    }

    /** Starts the gateway front end on one event loop. */
    void startGateway(HttpServerOptions options) throws Exception {
        int[] port = new int[1];
        await(vertx.deployVerticle(new VerticleBase() {
            private HttpForwarder forwarder;

            @Override
            public Future<?> start() {
                TargetResolver resolver = lookup == null ? new TargetResolver(vertx)
                        : new TargetResolver(vertx, lookup);
                health = new HealthChecker(vertx, resolver);
                forwarder = new HttpForwarder(vertx, resolver, health);
                return vertx.createHttpServer(options.setHost("127.0.0.1").setPort(0))
                        .requestHandler(new Pipeline(holder, forwarder)).listen()
                        .onSuccess(s -> port[0] = s.actualPort());
            }

            @Override
            public Future<?> stop() {
                forwarder.close();
                return Future.succeededFuture();
            }
        }));
        gatewayPort = port[0];
    }

    Snapshot publish(String json) throws Exception {
        return publish(json, new PluginRegistry(List.of()));
    }

    Snapshot publish(String json, PluginRegistry registry) throws Exception {
        Snapshot s = new SnapshotCompiler(registry)
                .compile(ConfigDecoder.decode(json.getBytes(StandardCharsets.UTF_8)));
        holder.publish(s);
        if (health != null) {
            health.sync(s);
        }
        return s;
    }

    static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    void close() throws Exception {
        await(vertx.close());
    }
}
