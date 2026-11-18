package apigw.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.PemTrustOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The gateway process (JVM or native) serves every protocol, reports metrics we track, and drains on SIGTERM. */
class GatewayProcessIT {

    private static final Path TLS = Path.of(System.getProperty("gw.test.tls.dir"));
    private static Vertx vertx;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static Map<String, String> env(int http, int https, int status, Path config) {
        Map<String, String> env = new java.util.HashMap<>(Map.of(
                "GW_BIND_HOST", "127.0.0.1",
                "GW_HTTP_PORT", Integer.toString(http),
                "GW_HTTPS_PORT", Integer.toString(https),
                "GW_STATUS_PORT", Integer.toString(status),
                "GW_EVENT_LOOPS", "2",
                "GW_SHUTDOWN_GRACE_SECONDS", "5",
                "GW_TLS_CERT", TLS.resolve("server.pem").toString(),
                "GW_TLS_KEY", TLS.resolve("server-key.pem").toString()));
        if (config != null) {
            env.put("GW_CONFIG_FILE", config.toString());
        }
        return env;
    }

    @Test
    void servesAllProtocolsAndShutsDownGracefully(@TempDir Path dir) throws Exception {
        int http = Ports.free();
        int https = Ports.free();
        int status = Ports.free();
        // A valid config with no routes: ready, and every request is a routed 404.
        Path config = Files.writeString(dir.resolve("gateway.json"), "{\"schemaVersion\":1}");
        try (GatewayProcess gw = GatewayProcess.start(List.of(), env(http, https, status, config))) {
            String listening = gw.awaitLine(l -> l.contains("gateway listening"), Duration.ofSeconds(30));
            long spawnToListeningMs = gw.millisSinceStart();
            gw.awaitLine(l -> l.contains("\"msg\":\"ready revision="), Duration.ofSeconds(30));
            long spawnToReadyMs = gw.millisSinceStart();
            assertThat(listening).startsWith("{\"ts\":\"").contains("\"level\":\"INFO\"");

            assertThat(get(new HttpClientOptions(), false, http, "/x")).isEqualTo("HTTP_1_1 404");
            assertThat(get(new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2)
                    .setHttp2ClearTextUpgrade(false), false, http, "/x")).isEqualTo("HTTP_2 404");
            assertThat(get(new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2)
                    .setHttp2ClearTextUpgrade(true), false, http, "/x")).isEqualTo("HTTP_2 404");
            assertThat(get(tls().setProtocolVersion(HttpVersion.HTTP_2), true, https, "/x")).isEqualTo("HTTP_2 404");
            assertThat(get(tls().setProtocolVersion(HttpVersion.HTTP_1_1), true, https, "/x"))
                    .isEqualTo("HTTP_1_1 404");
            assertThat(get(new HttpClientOptions(), false, status, "/healthz")).isEqualTo("HTTP_1_1 200");
            assertThat(get(new HttpClientOptions(), false, status, "/readyz")).isEqualTo("HTTP_1_1 200");

            long rssKib = gw.rssKib();
            // Recorded for PROGRESS.md; dev-host numbers, not reference-host results.
            System.out.printf("[%s] spawn-to-listening=%dms spawn-to-ready=%dms rss-after-probes=%.1fMB%n",
                    GatewayProcess.mode(), spawnToListeningMs, spawnToReadyMs, rssKib / 1024.0);

            int exit = gw.terminate(Duration.ofSeconds(15));
            // 143 = 128 + SIGTERM: the conventional exit status after a handled SIGTERM.
            assertThat(exit).isEqualTo(143);
            assertThat(gw.output()).anyMatch(l -> l.contains("shutdown complete"));
            assertThat(gw.output())
                    .noneMatch(l -> l.contains("Exception in thread") || l.contains("UnsupportedFeature"));
        }
    }

    @Test
    void withoutAConfigSourceItServes503AndIsNotReady() throws Exception {
        int http = Ports.free();
        int status = Ports.free();
        Map<String, String> env = env(http, Ports.free(), status, null);
        try (GatewayProcess gw = GatewayProcess.start(List.of(), env)) {
            gw.awaitLine(l -> l.contains("no configuration source configured"), Duration.ofSeconds(30));
            assertThat(get(new HttpClientOptions(), false, http, "/x")).isEqualTo("HTTP_1_1 503");
            assertThat(get(new HttpClientOptions(), false, status, "/readyz")).isEqualTo("HTTP_1_1 503");
            assertThat(gw.terminate(Duration.ofSeconds(15))).isEqualTo(143);
        }
    }

    @Test
    void refusesToStartWithInvalidConfiguration() throws Exception {
        try (GatewayProcess gw = GatewayProcess.start(List.of(), Map.of("GW_HTTP_PORT", "notaport"))) {
            assertThat(gw.waitForExit(Duration.ofSeconds(30))).isEqualTo(2);
            assertThat(gw.output()).anyMatch(l -> l.contains("invalid startup configuration")
                    && l.contains("GW_HTTP_PORT must be an integer"));
        }
    }

    private static HttpClientOptions tls() {
        return new HttpClientOptions().setSsl(true).setUseAlpn(true).setVerifyHost(true)
                .setTrustOptions(new PemTrustOptions().addCertPath(TLS.resolve("ca.pem").toString()));
    }

    private static String get(HttpClientOptions options, boolean ssl, int port, String path) throws Exception {
        HttpClientAgent client = vertx.createHttpClient(options);
        try {
            Future<String> f = client.request(new RequestOptions().setMethod(HttpMethod.GET).setHost("localhost")
                            .setPort(port).setSsl(ssl).setURI(path))
                    .compose(req -> req.send())
                    .compose(resp -> resp.body().map(b -> resp.version() + " " + resp.statusCode()));
            return f.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } finally {
            client.close();
        }
    }
}
