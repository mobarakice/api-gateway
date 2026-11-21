package apigw.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CorsTest {

    private static final String CONSOLE = "https://console.example.test";
    private static Vertx vertx;
    private static HttpServer server;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer().requestHandler(new Cors(Set.of(CONSOLE), req -> req.response()
                .setStatusCode(200).end("ok"))).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    @AfterAll
    static void stop() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static HttpResponse<String> send(String method, String origin, boolean preflight) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.actualPort()
                + "/admin/v1/routes")).method(method, HttpRequest.BodyPublishers.noBody());
        if (origin != null) {
            b.header("origin", origin);
        }
        if (preflight) {
            b.header("access-control-request-method", "PUT").header("access-control-request-headers",
                    "authorization, if-match");
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void preflightFromTheConsoleIsAllowedWithExactHeaders() throws Exception {
        HttpResponse<String> r = send("OPTIONS", CONSOLE, true);
        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(r.headers().firstValue("access-control-allow-origin")).hasValue(CONSOLE);
        assertThat(r.headers().firstValue("access-control-allow-headers")).hasValue(Cors.ALLOW_HEADERS);
        assertThat(r.headers().firstValue("access-control-allow-credentials")).isEmpty();
    }

    @Test
    void otherOriginsGetNoCorsHeaders() throws Exception {
        assertThat(send("OPTIONS", "https://evil.example.test", true).statusCode()).isEqualTo(403);
        HttpResponse<String> r = send("GET", "https://evil.example.test", false);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("access-control-allow-origin")).isEmpty();
        HttpResponse<String> ok = send("GET", CONSOLE, false);
        assertThat(ok.headers().firstValue("access-control-allow-origin")).hasValue(CONSOLE);
        assertThat(ok.headers().firstValue("access-control-expose-headers")).hasValueSatisfying(
                h -> assertThat(h).contains("etag"));
        assertThat(send("GET", null, false).headers().firstValue("access-control-allow-origin")).isEmpty();
    }

    @Test
    void originsMustBeExact() {
        assertThat(Cors.requireOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        String[] bad = {"*", "https://a.test/", "https://a.test/x", "ftp://a.test", "https://u@a.test",
            "https://*.a.test", "null"};
        for (String origin : bad) {
            assertThatThrownBy(() -> Cors.requireOrigin(origin)).as(origin)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
