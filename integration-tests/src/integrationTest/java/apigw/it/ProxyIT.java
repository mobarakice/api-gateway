package apigw.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end proxying through the real gateway process (JVM or native binary): routing, plugin
 * chain, load balancing, streaming, h2/h2c, generic errors, and live config reload from a file.
 */
class ProxyIT {

    private static Vertx vertx;
    private static HttpServer echoA;
    private static HttpServer echoB;

    @BeforeAll
    static void upstreams() throws Exception {
        vertx = Vertx.vertx();
        echoA = echo("a");
        echoB = echo("b");
    }

    @AfterAll
    static void tearDown() throws Exception {
        await(vertx.close());
    }

    private static HttpServer echo(String name) throws Exception {
        return await(vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0)
                .setHttp2ClearTextEnabled(true)).requestHandler(req -> {
                    long[] n = new long[1];
                    req.handler(b -> n[0] += b.length());
                    req.endHandler(v -> {
                        JsonObject h = new JsonObject();
                        req.headers().forEach(e -> h.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue()));
                        req.response().putHeader("content-type", "application/json").putHeader("server", "echo")
                                .end(new JsonObject().put("upstream", name).put("uri", req.uri())
                                        .put("version", req.version().name()).put("bodyBytes", n[0])
                                        .put("headers", h).encode());
                    });
                }).listen());
    }

    private static String config(long revision, String extraRoute) {
        return """
                {"schemaVersion": 1, "revision": REVISION,
                 "settings": {"upstreamAllowCidrs": ["127.0.0.0/8"]},
                 "upstreams": [
                   {"id": "pair", "balancer": "round-robin",
                    "targets": [{"host": "127.0.0.1", "port": PORT_A}, {"host": "127.0.0.1", "port": PORT_B}]},
                   {"id": "h2", "http2": true, "targets": [{"host": "127.0.0.1", "port": PORT_A}]},
                   {"id": "dead", "targets": [{"host": "127.0.0.1", "port": 1}]}],
                 "services": [{"id": "s", "upstreamId": "pair"}, {"id": "s-h2", "upstreamId": "h2"},
                              {"id": "s-dead", "upstreamId": "dead"}],
                 "routes": [
                   {"id": "users", "path": "/users/{id}", "match": "template", "methods": ["GET", "POST"],
                    "serviceId": "s",
                    "plugins": [{"name": "headers", "config": {"request": {"set": {"x-route": "users"}},
                                                              "response": {"remove": ["server"],
                                                                           "set": {"x-gw": "1"}}}}]},
                   {"id": "h2", "path": "/h2", "serviceId": "s-h2"},
                   {"id": "dead", "path": "/dead", "serviceId": "s-dead"}EXTRA_ROUTE]}
                """.replace("REVISION", Long.toString(revision))
                .replace("PORT_A", Integer.toString(echoA.actualPort()))
                .replace("PORT_B", Integer.toString(echoB.actualPort()))
                .replace("EXTRA_ROUTE", extraRoute);
    }

    /** Replaces the config atomically (write temp + rename), as an operator or a sidecar would. */
    private static void writeConfig(Path file, String json) throws Exception {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    record Resp(int status, MultiMap headers, Buffer body, HttpVersion version) {
        JsonObject json() {
            return body.toJsonObject();
        }
    }

    private static Resp call(HttpClientOptions o, HttpMethod m, int port, String uri, Buffer body) throws Exception {
        HttpClientAgent c = vertx.createHttpClient(o);
        try {
            return await(c.request(new RequestOptions().setMethod(m).setHost("127.0.0.1").setPort(port).setURI(uri))
                    .compose(req -> body == null ? req.send() : req.send(body))
                    .compose(r -> r.body().map(b -> new Resp(r.statusCode(), r.headers(), b, r.version()))));
        } finally {
            await(c.close());
        }
    }

    @Test
    void proxiesThroughTheRealProcess(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("gateway.json");
        writeConfig(file, config(1, ""));
        int http = Ports.free();
        int status = Ports.free();
        Map<String, String> env = new HashMap<>(Map.of("GW_BIND_HOST", "127.0.0.1",
                "GW_HTTP_PORT", Integer.toString(http), "GW_HTTPS_PORT", Integer.toString(Ports.free()),
                "GW_STATUS_PORT", Integer.toString(status), "GW_EVENT_LOOPS", "2",
                "GW_SHUTDOWN_GRACE_SECONDS", "5", "GW_CONFIG_FILE", file.toString(), "GW_CONFIG_POLL_MS", "100"));
        try (GatewayProcess gw = GatewayProcess.start(List.of(), env)) {
            gw.awaitLine(l -> l.contains("\"msg\":\"ready revision=1"), Duration.ofSeconds(30));
            HttpClientOptions h1 = new HttpClientOptions();

            Resp r = call(h1, HttpMethod.GET, http, "/users/42?x=1", null);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.json().getString("uri")).isEqualTo("/users/42?x=1");
            assertThat(r.json().getJsonObject("headers").getString("x-route")).isEqualTo("users");
            assertThat(r.json().getJsonObject("headers").getString("x-forwarded-for")).isEqualTo("127.0.0.1");
            assertThat(r.headers().get("x-gw")).isEqualTo("1");
            assertThat(r.headers().contains("server")).isFalse();
            assertThat(r.headers().get("x-request-id")).matches("[0-9a-f]{16}");

            // Round robin over two targets, per event loop: both are used.
            java.util.Set<String> used = new java.util.HashSet<>();
            for (int i = 0; i < 8; i++) {
                used.add(call(h1, HttpMethod.GET, http, "/users/" + i, null).json().getString("upstream"));
            }
            assertThat(used).containsExactlyInAnyOrder("a", "b");

            Buffer big = Buffer.buffer(new byte[3 * 1024 * 1024]);
            assertThat(call(h1, HttpMethod.POST, http, "/users/1", big).json().getLong("bodyBytes"))
                    .isEqualTo(big.length());

            Resp h2 = call(new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2)
                    .setHttp2ClearTextUpgrade(false), HttpMethod.GET, http, "/h2", null);
            assertThat(h2.version()).isEqualTo(HttpVersion.HTTP_2);
            assertThat(h2.json().getString("version")).isEqualTo("HTTP_2");

            Resp dead = call(h1, HttpMethod.GET, http, "/dead", null);
            assertThat(dead.status()).isEqualTo(502);
            assertThat(dead.body().toString()).doesNotContain("127.0.0.1").contains("\"title\":\"Bad Gateway\"");
            assertThat(call(h1, HttpMethod.DELETE, http, "/users/1", null).status()).isEqualTo(404);

            // Live reload: a new route appears without a restart.
            writeConfig(file, config(2, ", {\"id\": \"new\", \"path\": \"/new\", \"serviceId\": \"s\"}"));
            Awaitility.await().atMost(Duration.ofSeconds(10))
                    .until(() -> call(h1, HttpMethod.GET, http, "/new", null).status() == 200);

            // An invalid change is rejected and the previous snapshot keeps serving.
            writeConfig(file, config(3, ", {\"id\": \"bad\", \"path\": \"/bad\", \"serviceId\": \"nope\"}"));
            gw.awaitLine(l -> l.contains("config file change rejected"), Duration.ofSeconds(10));
            assertThat(call(h1, HttpMethod.GET, http, "/new", null).status()).isEqualTo(200);
            assertThat(call(new HttpClientOptions(), HttpMethod.GET, status, "/readyz", null).status()).isEqualTo(200);

            assertThat(gw.terminate(Duration.ofSeconds(15))).isEqualTo(143);
            assertThat(gw.output()).noneMatch(l -> l.contains("Exception in thread")
                    || l.contains("UnsupportedFeature") || l.contains("NoClassDefFoundError")
                    || l.contains("\"level\":\"ERROR\"") && !l.contains("rejected"));
        }
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
