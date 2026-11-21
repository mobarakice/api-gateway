package apigw.it;

import static org.assertj.core.api.Assertions.assertThat;

import apigw.testsupport.RedisTestNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The M5 traffic plugins through two real gateway processes (JVM or native binary) sharing one real
 * Redis (official image, ACL user limited to the commands and {@code gw*} keys the gateway uses):
 * global rate limits, Redis down (paused) with fail-open and fail-closed, cache L2, miss coalescing,
 * and a smoke test of each plugin.
 */
class TrafficIT {

    private static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static Vertx vertx;
    private static RedisTestNode redis;
    private static GatewayProcess gwA;
    private static GatewayProcess gwB;
    private static volatile int portA;
    private static volatile int portB;

    @TempDir
    static Path dir;

    @BeforeAll
    static void start() throws Exception {
        vertx = Vertx.vertx();
        HttpServer echo = await(vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0))
                .requestHandler(req -> req.body().onSuccess(body -> {
                    int n = HITS.computeIfAbsent(req.path(), k -> new AtomicInteger()).incrementAndGet();
                    var resp = req.response().putHeader("content-type", "application/json");
                    if (req.path().startsWith("/c/")) {
                        resp.putHeader("cache-control", "max-age=60");
                    }
                    String out = new JsonObject().put("uri", req.uri()).put("n", n).put("bodyBytes", body.length())
                            .encode();
                    long delay = Long.parseLong(req.getParam("delay", "0"));
                    if (delay > 0) {
                        vertx.setTimer(delay, id -> resp.end(out));
                    } else {
                        resp.end(out);
                    }
                })).listen());
        redis = new RedisTestNode().start();
        Path config = dir.resolve("gateway.json");
        Files.writeString(config, config(echo.actualPort()));
        Path pw = dir.resolve("redis-password");
        Files.writeString(pw, redis.password());
        portA = Ports.free();
        portB = Ports.free();
        gwA = GatewayProcess.start(List.of(), env(portA, config, pw));
        gwB = GatewayProcess.start(List.of(), env(portB, config, pw));
        for (GatewayProcess g : List.of(gwA, gwB)) {
            g.awaitLine(l -> l.contains("\"msg\":\"ready revision="), Duration.ofSeconds(60));
            g.awaitLine(l -> l.contains("redis connected"), Duration.ofSeconds(30));
        }
    }

    @AfterAll
    static void stop() throws Exception {
        for (AutoCloseable c : new AutoCloseable[] {gwA, gwB, redis}) {
            if (c != null) {
                c.close();
            }
        }
        if (vertx != null) {
            await(vertx.close());
        }
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static Map<String, String> env(int http, Path config, Path pw) {
        Map<String, String> env = new HashMap<>(Map.of("GW_BIND_HOST", "127.0.0.1",
                "GW_HTTP_PORT", Integer.toString(http), "GW_HTTPS_PORT", Integer.toString(Ports.free()),
                "GW_STATUS_PORT", Integer.toString(Ports.free()), "GW_EVENT_LOOPS", "2",
                "GW_SHUTDOWN_GRACE_SECONDS", "2", "GW_CONFIG_FILE", config.toString(), "GW_CONFIG_POLL_MS", "200"));
        env.put("GW_REDIS_URL", redis.url());
        env.put("GW_REDIS_USER", RedisTestNode.USER);
        env.put("GW_REDIS_PASSWORD_FILE", pw.toString());
        env.put("GW_REDIS_TIMEOUT_MS", "100");
        return env;
    }

    private static String route(String id, String path, String plugins) {
        return "{\"id\":\"" + id + "\",\"path\":\"" + path + "\",\"serviceId\":\"s\",\"plugins\":[" + plugins + "]}";
    }

    private static String config(int upstream) {
        String global = "{\"name\":\"rate-limit\",\"config\":{\"algorithm\":\"fixed-window\",\"limit\":6,"
                + "\"windowSeconds\":3600,\"key\":[\"header:x-client\"],\"mode\":\"global\",\"global\":{\"prefetch\":1,"
                + "\"timeoutMs\":100,\"failMode\":\"FM\"}}}";
        String routes = String.join(",", List.of(
                route("g-open", "/g-open", global.replace("FM", "open")),
                route("g-closed", "/g-closed", global.replace("FM", "closed")),
                route("tb", "/tb", "{\"name\":\"rate-limit\",\"config\":{\"limit\":4,\"windowSeconds\":3600,"
                        + "\"key\":[\"header:x-client\"],\"mode\":\"global\",\"global\":{\"prefetch\":2}}}"),
                route("c", "/c", "{\"name\":\"cache\",\"config\":{\"l2\":true,\"l2TimeoutMs\":100}}"),
                route("ip", "/ip", "{\"name\":\"ip-restriction\",\"config\":{\"deny\":[\"127.0.0.0/8\"]}}"),
                route("cors", "/cors", "{\"name\":\"cors\",\"config\":{\"allowOrigins\":[\"https://app.test\"]}}"),
                route("size", "/size", "{\"name\":\"request-size-limit\",\"config\":{\"maxBytes\":4}}"),
                route("tx", "/tx", "{\"name\":\"request-transform\",\"config\":{\"path\":{\"stripPrefix\":\"/tx\","
                        + "\"addPrefix\":\"/v9\"},\"query\":{\"set\":{\"via\":\"gw\"}}}}")));
        return "{\"schemaVersion\":1,\"revision\":1,\"settings\":{\"upstreamAllowCidrs\":[\"127.0.0.0/8\"]},"
                + "\"upstreams\":[{\"id\":\"u\",\"targets\":[{\"host\":\"127.0.0.1\",\"port\":" + upstream + "}]}],"
                + "\"services\":[{\"id\":\"s\",\"upstreamId\":\"u\"}],\"routes\":[" + routes + "]}";
    }

    private static HttpResponse<String> get(int port, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int hits(String path) {
        AtomicInteger n = HITS.get(path);
        return n == null ? 0 : n.get();
    }

    @Test
    void globalLimitIsSharedByBothGateways() throws Exception {
        int ok = 0;
        int limited = 0;
        for (int i = 0; i < 12; i++) {
            int status = get(i % 2 == 0 ? portA : portB, "/g-open/x", "x-client", "shared").statusCode();
            ok += status == 200 ? 1 : 0;
            limited += status == 429 ? 1 : 0;
        }
        // prefetch 1: Redis hands out exactly the 6 units, whichever node asks.
        assertThat(ok).isEqualTo(6);
        assertThat(limited).isEqualTo(6);
        // Pre-fetch 2: a node may hold a unit the other cannot use, never more than the limit in total.
        int tbOk = 0;
        for (int i = 0; i < 10; i++) {
            tbOk += get(i % 2 == 0 ? portA : portB, "/tb/x", "x-client", "tb").statusCode() == 200 ? 1 : 0;
        }
        assertThat(tbOk).isBetween(3, 4);
    }

    @Test
    void redisDownFailsOpenToLocalLimitsOrClosed() throws Exception {
        redis.pause();
        try {
            long start = System.nanoTime();
            HttpResponse<String> open = get(portA, "/g-open/x", "x-client", "down-1");
            HttpResponse<String> closed = get(portA, "/g-closed/x", "x-client", "down-2");
            assertThat(open.statusCode()).isEqualTo(200);
            assertThat(closed.statusCode()).isEqualTo(503);
            assertThat(closed.headers().firstValue("retry-after")).hasValue("1");
            assertThat(closed.body()).doesNotContain("redis").doesNotContain("Redis");
            // Bounded by the 100 ms command timeout, not hung on the frozen server.
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(3000);
            // Fail-open still limits: this node's local limiter applies (6 per key).
            int ok = 0;
            for (int i = 0; i < 10; i++) {
                ok += get(portA, "/g-open/x", "x-client", "down-3").statusCode() == 200 ? 1 : 0;
            }
            assertThat(ok).isEqualTo(6);
            // The cache keeps working from L1 and upstream while L2 is gone.
            assertThat(get(portA, "/c/down").statusCode()).isEqualTo(200);
            assertThat(get(portA, "/c/down").headers().firstValue("x-cache")).hasValue("HIT");
        } finally {
            redis.resume();
        }
        // Back to global limiting once Redis answers again (the client reconnects on its own).
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).until(() ->
                get(portB, "/g-closed/x", "x-client", "after-" + System.nanoTime()).statusCode() == 200);
        assertThat(gwA.awaitLine(l -> l.contains("global rate limit degraded"), Duration.ofSeconds(5)))
                .doesNotContain(redis.password());
    }

    @Test
    void cacheL2IsSharedAndMissesAreCoalesced() throws Exception {
        HttpResponse<String> first = get(portA, "/c/shared");
        assertThat(first.headers().firstValue("x-cache")).hasValue("MISS");
        // Gateway B has an empty L1 but finds the entry in Redis.
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
                "HIT".equals(get(portB, "/c/shared").headers().firstValue("x-cache").orElse("")));
        assertThat(get(portB, "/c/shared").body()).isEqualTo(first.body());
        assertThat(hits("/c/shared")).isEqualTo(1);

        List<CompletableFuture<HttpResponse<String>>> all = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            all.add(HTTP.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + portA
                    + "/c/burst?delay=400")).GET().build(), HttpResponse.BodyHandlers.ofString()));
        }
        for (var f : all) {
            assertThat(f.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        assertThat(hits("/c/burst")).isEqualTo(1);
    }

    @Test
    void pluginSmoke() throws Exception {
        assertThat(get(portA, "/ip/x").statusCode()).isEqualTo(403);
        HttpResponse<String> pre = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + portB + "/cors/x"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).header("origin", "https://app.test")
                .header("access-control-request-method", "GET").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(pre.statusCode()).isEqualTo(204);
        assertThat(pre.headers().firstValue("access-control-allow-origin")).hasValue("https://app.test");
        HttpResponse<String> big = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + portA + "/size/x"))
                .POST(HttpRequest.BodyPublishers.ofString("12345")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(big.statusCode()).isEqualTo(413);
        assertThat(new JsonObject(get(portB, "/tx/a?b=1").body()).getString("uri")).isEqualTo("/v9/a?b=1&via=gw");
        // No secrets in logs: the Redis password never appears in either gateway's output.
        assertThat(gwA.output()).noneMatch(l -> l.contains(redis.password()));
        assertThat(gwB.output()).noneMatch(l -> l.contains(redis.password()));
    }
}
