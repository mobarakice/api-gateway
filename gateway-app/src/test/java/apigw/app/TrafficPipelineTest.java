package apigw.app;

import static org.assertj.core.api.Assertions.assertThat;

import apigw.core.plugin.PluginServices;
import apigw.core.pipeline.Pipeline;
import apigw.core.snapshot.Snapshot;
import apigw.core.snapshot.SnapshotCompiler;
import apigw.core.snapshot.SnapshotHolder;
import apigw.model.ApiKeyHash;
import apigw.model.ConfigDecoder;
import apigw.model.ConfigException;
import apigw.plugins.BuiltinPlugins;
import apigw.plugins.cache.ResponseCache;
import apigw.plugins.ratelimit.RateLimitStore;
import apigw.proxy.HttpForwarder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The M5 traffic plugins in process: real pipeline, forwarder and echo upstream. Local rate limits and
 * the L1 cache only (Redis cases run in the integration suite against a real Redis container). Runs on
 * the JVM and as a native image ({@code nativeTest}).
 */
class TrafficPipelineTest {

    private static final String SECRET = ApiKeyHash.newSecret();
    private static Vertx vertx;
    private static SnapshotCompiler compiler;
    private static final SnapshotHolder HOLDER = new SnapshotHolder();
    private static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<>();
    private static volatile int upstreamPort;
    private static volatile int gatewayPort;
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void start() throws Exception {
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2));
        HttpServer upstream = await(vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0))
                .requestHandler(req -> req.body().onSuccess(body -> {
                    String path = req.path();
                    int n = HITS.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
                    var resp = req.response().putHeader("content-type", "application/json");
                    if (path.startsWith("/c/") || path.startsWith("/cache/")) {
                        String cc = req.getParam("cc", "max-age=60");
                        resp.putHeader("cache-control", cc);
                    }
                    if (path.endsWith("/cookie")) {
                        resp.putHeader("set-cookie", "s=1");
                    }
                    if (path.endsWith("/vary-star")) {
                        resp.putHeader("vary", "*");
                    }
                    if (path.endsWith("/vary-accept")) {
                        resp.putHeader("vary", "Accept");
                    }
                    if (path.endsWith("/tagged")) {
                        resp.putHeader("cache-tag", "t1 t2");
                    }
                    if (path.endsWith("/cors-upstream")) {
                        resp.putHeader("access-control-allow-origin", "*");
                    }
                    JsonObject h = new JsonObject();
                    req.headers().forEach(e -> h.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue()));
                    String out = new JsonObject().put("path", path).put("uri", req.uri()).put("n", n)
                            .put("bodyBytes", body.length()).put("headers", h).encode();
                    String xd = req.getHeader("x-delay");
                    long delay = Long.parseLong(xd != null ? xd : req.getParam("delay", "0"));
                    if (delay > 0) {
                        vertx.setTimer(delay, id -> resp.end(out));
                    } else {
                        resp.end(out);
                    }
                })).listen());
        upstreamPort = upstream.actualPort();
        compiler = new SnapshotCompiler(BuiltinPlugins.registry(), new PluginServices(Map.of(
                RateLimitStore.class, new RateLimitStore(10_000, null),
                ResponseCache.class, new ResponseCache(8L << 20, null))), Clock.systemUTC());
        publish(config(""));
        int[] port = new int[1];
        await(vertx.deployVerticle(() -> new VerticleBase() {
            @Override
            public Future<?> start() {
                return vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0))
                        .requestHandler(new Pipeline(HOLDER, new HttpForwarder(vertx))).listen()
                        .onSuccess(s -> port[0] = s.actualPort());
            }
        }, new io.vertx.core.DeploymentOptions().setInstances(1)));
        gatewayPort = port[0];
    }

    @AfterAll
    static void stop() throws Exception {
        await(vertx.close());
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private static Snapshot publish(String json) throws ConfigException {
        Snapshot s = compiler.compile(ConfigDecoder.decode(json.getBytes(StandardCharsets.UTF_8)));
        HOLDER.publish(s);
        compiler.services().published(s.resources());
        return s;
    }

    private static String route(String id, String path, String match, String plugins) {
        return "{\"id\":\"" + id + "\",\"path\":\"" + path + "\",\"match\":\"" + match + "\",\"serviceId\":\"s\","
                + "\"plugins\":[" + plugins + "]}";
    }

    private static String config(String extra) {
        String routes = String.join(",", List.of(
                route("ip", "/ip", "prefix", "{\"name\":\"ip-restriction\",\"config\":{\"allow\":[\"127.0.0.0/8\","
                        + "\"10.0.0.0/8\"],\"deny\":[\"10.6.6.0/24\"]}}"),
                route("cors", "/cors", "prefix", "{\"name\":\"cors\",\"config\":{\"allowOrigins\":[\"https://app.test\","
                        + "\"https://*.example.test\"],\"allowMethods\":[\"GET\",\"PUT\"],\"allowHeaders\":"
                        + "[\"x-api-key\",\"x-custom\"],\"exposeHeaders\":[\"x-request-id\"],"
                        + "\"allowCredentials\":true}},"
                        + "{\"name\":\"key-auth\",\"config\":{}}"),
                route("size", "/size", "prefix", "{\"name\":\"request-size-limit\",\"config\":{\"maxBytes\":10}}"),
                route("tx", "/users/{id}/x", "template", "{\"name\":\"request-transform\",\"config\":{\"path\":"
                        + "{\"template\":\"/v2/u/{id}\"},\"query\":{\"remove\":[\"debug\"],"
                        + "\"rename\":{\"q\":\"query\"},"
                        + "\"set\":{\"src\":\"gw tx\"},\"add\":{\"tag\":\"a\"}},\"headers\":{\"rename\":"
                        + "{\"x-old\":\"x-new\"}}}}"),
                route("strip", "/api", "prefix", "{\"name\":\"request-transform\",\"config\":{\"path\":"
                        + "{\"stripPrefix\":\"/api\",\"addPrefix\":\"/internal\"}}}"),
                route("rl", "/rl", "prefix", "{\"name\":\"rate-limit\",\"config\":{\"algorithm\":\"fixed-window\","
                        + "\"limit\":3,\"windowSeconds\":60,\"key\":[\"header:x-client\"]}}"),
                route("rl-tb", "/tb", "prefix", "{\"name\":\"rate-limit\",\"config\":{\"limit\":2,\"burst\":2,"
                        + "\"windowSeconds\":1,\"key\":[\"ip\",\"route\"],\"name\":\"tb\"}}"),
                route("rl-sw", "/sw", "prefix", "{\"name\":\"rate-limit\",\"config\":{\"algorithm\":"
                        + "\"sliding-window\",\"limit\":2,\"windowSeconds\":60}}"),
                route("cache", "/c", "prefix", "{\"name\":\"cache\",\"config\":{\"vary\":[\"accept\"],"
                        + "\"staleWhileRevalidateSeconds\":0,\"lockTimeoutMs\":3000}}"),
                route("cache-auth", "/cache", "prefix", "{\"name\":\"key-auth\",\"config\":{}},"
                        + "{\"name\":\"cache\",\"config\":{\"cacheAuthenticated\":true}}")));
        return "{\"schemaVersion\":1,\"settings\":{\"upstreamAllowCidrs\":[\"127.0.0.0/8\"],"
                + "\"trustedProxies\":[\"127.0.0.1/32\"]},"
                + "\"upstreams\":[{\"id\":\"u\",\"targets\":[{\"host\":\"127.0.0.1\",\"port\":" + upstreamPort + "}]}],"
                + "\"services\":[{\"id\":\"s\",\"upstreamId\":\"u\"}],"
                + "\"consumers\":[{\"id\":\"c1\"},{\"id\":\"c2\"}],"
                + "\"credentials\":[{\"id\":\"k1\",\"consumerId\":\"c1\",\"type\":\"api-key\",\"keyId\":\"key_00001\","
                + "\"hash\":\"" + ApiKeyHash.hash(SECRET) + "\"},{\"id\":\"k2\",\"consumerId\":\"c2\",\"type\":"
                + "\"api-key\",\"keyId\":\"key_00002\",\"hash\":\"" + ApiKeyHash.hash(SECRET) + "\"}],"
                + "\"routes\":[" + routes + "]" + extra + "}";
    }

    private static HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder req(String pathAndQuery) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + pathAndQuery))
                .timeout(Duration.ofSeconds(10));
    }

    private static HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder b = req(path).GET();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return send(b);
    }

    private static int hits(String path) {
        AtomicInteger n = HITS.get(path);
        return n == null ? 0 : n.get();
    }

    @Test
    void ipRestrictionUsesTheClientIpFromTrustedProxies() throws Exception {
        assertThat(get("/ip/a").statusCode()).isEqualTo(200);
        // The direct peer (127.0.0.1) is a trusted proxy: the client is the rightmost untrusted hop.
        assertThat(get("/ip/a", "x-forwarded-for", "10.1.2.3").statusCode()).isEqualTo(200);
        assertThat(get("/ip/a", "x-forwarded-for", "10.6.6.9").statusCode()).isEqualTo(403);
        assertThat(get("/ip/a", "x-forwarded-for", "8.8.8.8").statusCode()).isEqualTo(403);
        // Spoofing a left-most entry does not help: the rightmost untrusted hop decides.
        assertThat(get("/ip/a", "x-forwarded-for", "10.1.2.3, 8.8.8.8").statusCode()).isEqualTo(403);
    }

    @Test
    void corsPreflightActualAndErrorResponses() throws Exception {
        HttpResponse<String> pre = send(req("/cors/x").method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("origin", "https://app.test").header("access-control-request-method", "PUT")
                .header("access-control-request-headers", "X-Custom, content-type"));
        assertThat(pre.statusCode()).isEqualTo(204);
        assertThat(pre.headers().firstValue("access-control-allow-origin")).hasValue("https://app.test");
        assertThat(pre.headers().firstValue("access-control-allow-credentials")).hasValue("true");
        assertThat(pre.headers().firstValue("access-control-allow-methods")).hasValue("GET, PUT");
        assertThat(hits("/cors/x")).isZero();
        assertThat(send(req("/cors/x").method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("origin", "https://evil.test").header("access-control-request-method", "GET"))
                .statusCode()).isEqualTo(403);
        assertThat(send(req("/cors/x").method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("origin", "https://app.test").header("access-control-request-method", "DELETE"))
                .statusCode()).isEqualTo(403);
        // Subdomain wildcard, on an error response (no API key -> 401) the browser can still read it.
        HttpResponse<String> denied = get("/cors/x", "origin", "https://a.b.example.test");
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(denied.headers().firstValue("access-control-allow-origin")).hasValue("https://a.b.example.test");
        assertThat(denied.headers().firstValue("access-control-expose-headers")).hasValue("x-request-id");
        HttpResponse<String> ok = get("/cors/cors-upstream", "origin", "https://app.test",
                "x-api-key", "key_00001." + SECRET);
        assertThat(ok.statusCode()).isEqualTo(200);
        // The upstream's "*" is replaced by the gateway's policy.
        assertThat(ok.headers().allValues("access-control-allow-origin")).containsExactly("https://app.test");
        assertThat(ok.headers().allValues("vary")).anyMatch(v -> v.contains("Origin"));
        HttpResponse<String> other = get("/cors/x", "origin", "https://example.test.evil.com",
                "x-api-key", "key_00001." + SECRET);
        assertThat(other.headers().firstValue("access-control-allow-origin")).isEmpty();
    }

    @Test
    void requestSizeLimitCapsDeclaredAndChunkedBodies() throws Exception {
        assertThat(send(req("/size/a").POST(HttpRequest.BodyPublishers.ofString("0123456789"))).statusCode())
                .isEqualTo(200);
        assertThat(send(req("/size/a").POST(HttpRequest.BodyPublishers.ofString("0123456789X"))).statusCode())
                .isEqualTo(413);
        // No Content-Length: chunked, counted while streaming.
        HttpResponse<String> chunked = send(req("/size/b").POST(HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream(new byte[5000]))));
        assertThat(chunked.statusCode()).isEqualTo(413);
        HttpResponse<String> small = send(req("/size/c").POST(HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream(new byte[4]))));
        assertThat(small.statusCode()).isEqualTo(200);
        assertThat(new JsonObject(small.body()).getInteger("bodyBytes")).isEqualTo(4);
    }

    @Test
    void requestTransformRewritesPathQueryAndHeaders() throws Exception {
        JsonObject r = new JsonObject(get("/users/42/x?debug=1&q=cats&src=client&keep=1", "x-old", "v").body());
        assertThat(r.getString("path")).isEqualTo("/v2/u/42");
        assertThat(r.getString("uri")).isEqualTo("/v2/u/42?query=cats&keep=1&src=gw%20tx&tag=a");
        assertThat(r.getJsonObject("headers").getString("x-new")).isEqualTo("v");
        assertThat(r.getJsonObject("headers").containsKey("x-old")).isFalse();
        assertThat(new JsonObject(get("/api/orders/7?x=1").body()).getString("uri"))
                .isEqualTo("/internal/orders/7?x=1");
        assertThat(new JsonObject(get("/api").body()).getString("uri")).isEqualTo("/internal");
    }

    @Test
    void fixedWindowLimitReturns429WithRetryAfterAndHeaders() throws Exception {
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> ok = get("/rl/a", "x-client", "alice");
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.headers().firstValue("ratelimit-policy")).hasValue("\"default\";q=3;w=60");
            String expected = "\"default\";r=" + (2 - i) + ";t=";
            assertThat(ok.headers().firstValue("ratelimit")).hasValueSatisfying(v ->
                    assertThat(v).startsWith(expected));
        }
        HttpResponse<String> limited = get("/rl/a", "x-client", "alice");
        assertThat(limited.statusCode()).isEqualTo(429);
        long retry = Long.parseLong(limited.headers().firstValue("retry-after").orElseThrow());
        assertThat(retry).isBetween(1L, 60L);
        assertThat(limited.body()).contains("Too Many Requests").doesNotContain("alice");
        // Another key has its own quota; a missing header falls back to the client IP.
        assertThat(get("/rl/a", "x-client", "bob").statusCode()).isEqualTo(200);
        assertThat(get("/rl/a").statusCode()).isEqualTo(200);
    }

    @Test
    void tokenBucketRefillsAndSlidingWindowLimits() throws Exception {
        assertThat(get("/tb/a").statusCode()).isEqualTo(200);
        assertThat(get("/tb/a").statusCode()).isEqualTo(200);
        assertThat(get("/tb/a").statusCode()).isEqualTo(429);
        // 2 tokens per second: one comes back after ~500 ms.
        Thread.sleep(600);
        assertThat(get("/tb/a").statusCode()).isEqualTo(200);
        assertThat(get("/sw/a").statusCode()).isEqualTo(200);
        assertThat(get("/sw/a").statusCode()).isEqualTo(200);
        assertThat(get("/sw/a").statusCode()).isEqualTo(429);
    }

    @Test
    void cacheHitsVaryAndNeverStoresUnsafeResponses() throws Exception {
        HttpResponse<String> first = get("/c/a", "accept", "application/json");
        assertThat(first.headers().firstValue("x-cache")).hasValue("MISS");
        HttpResponse<String> second = get("/c/a", "accept", "application/json");
        assertThat(second.headers().firstValue("x-cache")).hasValue("HIT");
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.headers().firstValue("age")).isPresent();
        assertThat(hits("/c/a")).isEqualTo(1);
        // HEAD is answered from the GET entry.
        HttpResponse<String> head = send(req("/c/a").method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("accept", "application/json"));
        assertThat(head.headers().firstValue("x-cache")).hasValue("HIT");
        // A different Accept is a different entry; query order does not matter.
        get("/c/a", "accept", "text/plain");
        assertThat(hits("/c/a")).isEqualTo(2);
        get("/c/q?b=2&a=1");
        assertThat(get("/c/q?a=1&b=2").headers().firstValue("x-cache")).hasValue("HIT");
        // Never stored.
        for (String p : new String[] {"/c/cookie", "/c/vary-star", "/c/ns?cc=no-store", "/c/pv?cc=private,max-age=60",
                "/c/nc?cc=no-cache"}) {
            get(p);
            assertThat(get(p).headers().firstValue("x-cache")).as(p).hasValue("MISS");
        }
        // Vary on a configured header is fine.
        get("/c/vary-accept", "accept", "a/b");
        assertThat(get("/c/vary-accept", "accept", "a/b").headers().firstValue("x-cache")).hasValue("HIT");
        // Requests with credentials bypass a cache that does not isolate principals.
        assertThat(get("/c/a", "accept", "application/json", "authorization", "Bearer x")
                .headers().firstValue("x-cache")).hasValue("BYPASS");
        assertThat(get("/c/a", "accept", "application/json", "cache-control", "no-store")
                .headers().firstValue("x-cache")).hasValue("BYPASS");
    }

    @Test
    void authenticatedCacheIsolatesPrincipals() throws Exception {
        String a = get("/cache/me", "x-api-key", "key_00001." + SECRET).body();
        assertThat(get("/cache/me", "x-api-key", "key_00001." + SECRET).headers().firstValue("x-cache"))
                .hasValue("HIT");
        HttpResponse<String> other = get("/cache/me", "x-api-key", "key_00002." + SECRET);
        assertThat(other.headers().firstValue("x-cache")).hasValue("MISS");
        assertThat(other.body()).isNotEqualTo(a);
        assertThat(new JsonObject(other.body()).getJsonObject("headers").getString("x-consumer-id")).isEqualTo("c2");
    }

    @Test
    void concurrentMissesAreCoalescedIntoOneUpstreamRequest() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> all = new ArrayList<>();
        HttpClient h2 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        for (int i = 0; i < 20; i++) {
            all.add(h2.sendAsync(req("/c/slow?delay=400").GET().build(), HttpResponse.BodyHandlers.ofString()));
        }
        List<String> bodies = new ArrayList<>();
        for (var f : all) {
            HttpResponse<String> r = f.get(10, TimeUnit.SECONDS);
            assertThat(r.statusCode()).isEqualTo(200);
            bodies.add(r.body());
        }
        assertThat(hits("/c/slow")).isEqualTo(1);
        assertThat(bodies).allMatch(b -> b.equals(bodies.getFirst()));
    }

    @Test
    void uncacheableLeaderReleasesWaitersAtOnce() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(HTTP.sendAsync(req("/c/uncacheable?delay=300&cc=no-store").GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        long start = System.nanoTime();
        for (var f : all) {
            assertThat(f.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        // Waiters were released at the leader's response head and fetched in parallel (not 5 x 300 ms).
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1200);
        assertThat(hits("/c/uncacheable")).isBetween(2, 5);
    }

    @Test
    void staleWhileRevalidateServesStaleDuringRefresh() throws Exception {
        String uri = "/c/swr?cc=max-age=1,stale-while-revalidate=30";
        String original = get(uri).body();
        Thread.sleep(1100);
        // Expired: this request leads the refresh (slow upstream), a concurrent one is served stale at once.
        CompletableFuture<HttpResponse<String>> leader = HTTP.sendAsync(req(uri).header("x-delay", "600").GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        Thread.sleep(150);
        long start = System.nanoTime();
        HttpResponse<String> stale = get(uri);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(400);
        assertThat(stale.headers().firstValue("x-cache")).hasValue("STALE");
        assertThat(stale.body()).isEqualTo(original);
        HttpResponse<String> lead = leader.get(10, TimeUnit.SECONDS);
        assertThat(lead.headers().firstValue("x-cache")).hasValue("EXPIRED");
        HttpResponse<String> fresh = get(uri);
        assertThat(fresh.headers().firstValue("x-cache")).hasValue("HIT");
        assertThat(fresh.body()).isEqualTo(lead.body()).isNotEqualTo(original);
    }

    @Test
    void purgesByRouteAndTagInvalidateLazily() throws Exception {
        get("/c/tagged");
        get("/c/plain");
        assertThat(get("/c/tagged").headers().firstValue("x-cache")).hasValue("HIT");
        String now = Instant.now().toString();
        // An entry stored in the purge's own millisecond counts as purged (conservative), so the re-store
        // below must land in a later millisecond; a native run can otherwise get there within 1 ms.
        Thread.sleep(2);
        publish(config(",\"cachePurges\":[{\"id\":\"p1\",\"tag\":\"t2\",\"issuedAt\":\"" + now + "\"}]"));
        try {
            assertThat(get("/c/tagged").headers().firstValue("x-cache")).hasValue("MISS");
            assertThat(get("/c/plain").headers().firstValue("x-cache")).hasValue("HIT");
            assertThat(get("/c/tagged").headers().firstValue("x-cache")).hasValue("HIT");
            Thread.sleep(5);
            String later = Instant.now().toString();
            Thread.sleep(2);
            publish(config(",\"cachePurges\":[{\"id\":\"p2\",\"routeId\":\"cache\",\"issuedAt\":\""
                    + later + "\"}]"));
            assertThat(get("/c/plain").headers().firstValue("x-cache")).hasValue("MISS");
        } finally {
            publish(config(""));
        }
    }

    @Test
    void mutualTlsToTheUpstreamPresentsTheConfiguredClientCertificate() throws Exception {
        java.nio.file.Path tls = java.nio.file.Path.of(System.getProperty("gw.test.tls.dir", "build/test-tls"));
        HttpServer secure = await(vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0)
                .setSsl(true).setClientAuth(io.vertx.core.http.ClientAuth.REQUIRED)
                .setKeyCertOptions(new io.vertx.core.net.PemKeyCertOptions()
                        .setCertPath(tls.resolve("server.pem").toString())
                        .setKeyPath(tls.resolve("server-key.pem").toString()))
                .setTrustOptions(new io.vertx.core.net.PemTrustOptions().addCertPath(tls.resolve("ca.pem").toString())))
                .requestHandler(req -> {
                    // ClientAuth.REQUIRED: reaching this handler means the gateway presented a CA-signed cert.
                    req.response().end("mtls-ok:" + (req.isSSL() ? 1 : 0));
                })
                .listen());
        String base = "{\"schemaVersion\":1,\"settings\":{\"upstreamAllowCidrs\":[\"127.0.0.0/8\"]},"
                + "\"upstreams\":[{\"id\":\"tls\",\"scheme\":\"https\",\"caFile\":\"" + tls.resolve("ca.pem")
                + "\",\"targets\":[{\"host\":\"127.0.0.1\",\"port\":" + secure.actualPort() + "}]CERT}],"
                + "\"services\":[{\"id\":\"s\",\"upstreamId\":\"tls\"}],"
                + "\"routes\":[{\"id\":\"m\",\"path\":\"/\",\"serviceId\":\"s\"}]}";
        try {
            publish(base.replace("CERT", ",\"clientCert\":{\"certFile\":\"" + tls.resolve("client.pem")
                    + "\",\"keyFile\":\"" + tls.resolve("client-key.pem") + "\"}"));
            HttpResponse<String> ok = get("/m");
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.body()).isEqualTo("mtls-ok:1");
            // Without a client certificate the upstream refuses the handshake: a generic 502.
            publish(base.replace("CERT", ""));
            HttpResponse<String> refused = get("/m");
            assertThat(refused.statusCode()).isEqualTo(502);
            assertThat(refused.body()).doesNotContain("127.0.0.1").doesNotContain("certificate");
        } finally {
            publish(config(""));
            await(secure.close());
        }
    }
}
