package apigw.app;

import static org.assertj.core.api.Assertions.assertThat;

import apigw.core.plugin.PluginRegistry;
import apigw.core.snapshot.SnapshotCompiler;
import apigw.core.snapshot.SnapshotHolder;
import apigw.model.ConfigDecoder;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.PemTrustOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Starts the real server in-process (JVM and nativeTest) and talks to it over every protocol it serves. */
class GatewayServerTest {

    private static final Path TLS = Path.of(System.getProperty("gw.test.tls.dir", "build/test-tls"));
    private static Vertx vertx;
    private static GatewayServer server;
    private static Readiness readiness;

    @BeforeAll
    static void start() throws Exception {
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2));
        readiness = new Readiness();
        BootstrapConfig config = new BootstrapConfig("127.0.0.1", 0, 0, 0, 2,
                Optional.of(new BootstrapConfig.TlsFiles(TLS.resolve("server.pem"), TLS.resolve("server-key.pem"))),
                Duration.ofSeconds(2));
        SnapshotHolder snapshots = new SnapshotHolder();
        // A valid config with no routes: every request is a routed 404; readiness is not marked.
        snapshots.publish(new SnapshotCompiler(new PluginRegistry(List.of()))
                .compile(ConfigDecoder.decode("{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8))));
        server = new GatewayServer(vertx, config, readiness, snapshots);
        await(server.start());
    }

    @AfterAll
    static void stop() throws Exception {
        await(server.stop());
        await(vertx.close());
    }

    @Test
    void servesHttp11OnPlaintextPort() throws Exception {
        Response r = get(new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_1_1), false, server.httpPort(),
                "/anything");
        assertThat(r.version).isEqualTo(HttpVersion.HTTP_1_1);
        assertNotFoundProblem(r);
    }

    @Test
    void servesH2cWithPriorKnowledge() throws Exception {
        Response r = get(new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(false), false, server.httpPort(), "/a");
        assertThat(r.version).isEqualTo(HttpVersion.HTTP_2);
        assertNotFoundProblem(r);
    }

    @Test
    void servesH2cViaUpgrade() throws Exception {
        Response r = get(new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(true), false, server.httpPort(), "/b");
        assertThat(r.version).isEqualTo(HttpVersion.HTTP_2);
        assertNotFoundProblem(r);
    }

    @Test
    void negotiatesH2OverTlsWithAlpn() throws Exception {
        Response r = get(tls().setProtocolVersion(HttpVersion.HTTP_2), true, server.httpsPort(), "/c");
        assertThat(r.version).isEqualTo(HttpVersion.HTTP_2);
        assertNotFoundProblem(r);
    }

    @Test
    void fallsBackToHttp11OverTls() throws Exception {
        Response r = get(tls().setProtocolVersion(HttpVersion.HTTP_1_1), true, server.httpsPort(), "/d");
        assertThat(r.version).isEqualTo(HttpVersion.HTTP_1_1);
        assertNotFoundProblem(r);
    }

    @Test
    void healthIsUpButNotReadyWithoutConfig() throws Exception {
        HttpClientOptions h1 = new HttpClientOptions();
        Response health = get(h1, false, server.statusPort(), "/healthz");
        assertThat(health.status).isEqualTo(200);
        assertThat(health.body).isEqualTo("{\"status\":\"UP\"}");

        Response ready = get(h1, false, server.statusPort(), "/readyz");
        assertThat(ready.status).isEqualTo(503);
        assertThat(ready.body).contains("NOT_READY").contains("no configuration snapshot loaded");
    }

    @Test
    void statusEndpointsAreNotOnThePublicPort() throws Exception {
        Response r = get(new HttpClientOptions(), false, server.httpPort(), "/healthz");
        assertNotFoundProblem(r);
    }

    private static void assertNotFoundProblem(Response r) {
        assertThat(r.status).isEqualTo(404);
        assertThat(r.contentType).isEqualTo("application/problem+json");
        assertThat(r.requestId).matches("[0-9a-f]{16}");
        assertThat(r.body).isEqualTo("{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,"
                + "\"requestId\":\"" + r.requestId + "\"}");
    }

    private static HttpClientOptions tls() {
        return new HttpClientOptions().setSsl(true).setUseAlpn(true).setVerifyHost(true)
                .setTrustOptions(new PemTrustOptions().addCertPath(TLS.resolve("ca.pem").toString()));
    }

    private static Response get(HttpClientOptions options, boolean ssl, int port, String path) throws Exception {
        HttpClientAgent client = vertx.createHttpClient(options);
        try {
            return await(client.request(new RequestOptions().setMethod(HttpMethod.GET).setHost("localhost")
                            .setPort(port).setSsl(ssl).setURI(path))
                    .compose(req -> req.send())
                    .compose((HttpClientResponse resp) -> resp.body().map(b -> new Response(resp.statusCode(),
                            resp.version(), resp.getHeader("content-type"), resp.getHeader("x-request-id"),
                            b.toString()))));
        } finally {
            await(client.close());
        }
    }

    private static <T> T await(io.vertx.core.Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private record Response(int status, HttpVersion version, String contentType, String requestId, String body) {
    }
}
