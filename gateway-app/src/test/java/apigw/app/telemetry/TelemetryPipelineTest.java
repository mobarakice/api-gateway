package apigw.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import apigw.core.observe.RouteTelemetryFactory;
import apigw.core.pipeline.Pipeline;
import apigw.core.plugin.PluginServices;
import apigw.core.snapshot.Snapshot;
import apigw.core.snapshot.SnapshotCompiler;
import apigw.core.snapshot.SnapshotHolder;
import apigw.model.ConfigDecoder;
import apigw.model.ConfigException;
import apigw.observability.metrics.MetricRegistry;
import apigw.observability.tracing.Span;
import apigw.observability.tracing.TraceParent;
import apigw.observability.tracing.Tracer;
import apigw.observability.tracing.TracingConfig;
import apigw.plugins.BuiltinPlugins;
import apigw.proxy.HttpForwarder;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Metrics, trace propagation and span export through the real pipeline, forwarder and an upstream that
 * echoes the {@code traceparent} it received. Runs on the JVM and as a native image.
 */
class TelemetryPipelineTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT = "00f067aa0ba902b7";
    private static final Queue<Span> SPANS = new ConcurrentLinkedQueue<>();
    private static final MetricRegistry METRICS = new MetricRegistry();
    private static final SnapshotHolder HOLDER = new SnapshotHolder();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static Vertx vertx;
    private static SnapshotCompiler compiler;
    private static int upstreamPort;
    private static int gatewayPort;

    @BeforeAll
    static void start() throws Exception {
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2));
        HttpServer upstream = get(vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0))
                .requestHandler(req -> req.response().putHeader("content-type", "application/json")
                        .end(new JsonObject().put("traceparent", req.getHeader("traceparent"))
                                .put("tracestate", req.getHeader("tracestate")).encode()))
                .listen());
        upstreamPort = upstream.actualPort();
        TracingConfig tc = new TracingConfig(URI.create("http://127.0.0.1:4318"), 1.0, true,
                Map.of("service.name", "test"), 64, 8, Duration.ofSeconds(1));
        GatewayTelemetry telemetry = new GatewayTelemetry(METRICS, TelemetryConfig.AccessLogMode.ALL,
                new Tracer(tc, SPANS::add));
        compiler = new SnapshotCompiler(BuiltinPlugins.registry(),
                new PluginServices(Map.of(RouteTelemetryFactory.class, telemetry)), Clock.systemUTC());
        publish(true);
        int[] port = new int[1];
        get(vertx.deployVerticle(() -> new VerticleBase() {
            @Override
            public Future<?> start() {
                return vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0))
                        .requestHandler(new Pipeline(HOLDER, new HttpForwarder(vertx), telemetry)).listen()
                        .onSuccess(s -> port[0] = s.actualPort());
            }
        }, new io.vertx.core.DeploymentOptions().setInstances(1)));
        gatewayPort = port[0];
    }

    @AfterAll
    static void stop() throws Exception {
        get(vertx.close());
    }

    private static <T> T get(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private static void publish(boolean withAuthRoute) throws ConfigException {
        String routes = "{\"id\":\"ok\",\"path\":\"/ok/{id}\",\"match\":\"template\",\"serviceId\":\"s\"},"
                + "{\"id\":\"dead\",\"path\":\"/dead\",\"serviceId\":\"d\"}"
                + (withAuthRoute ? ",{\"id\":\"auth\",\"path\":\"/auth\",\"serviceId\":\"s\","
                        + "\"plugins\":[{\"name\":\"key-auth\",\"config\":{}}]}" : "");
        String json = "{\"schemaVersion\":1,\"settings\":{\"upstreamAllowCidrs\":[\"127.0.0.0/8\"]},"
                + "\"upstreams\":[{\"id\":\"u\",\"targets\":[{\"host\":\"127.0.0.1\",\"port\":" + upstreamPort + "}]},"
                + "{\"id\":\"down\",\"targets\":[{\"host\":\"127.0.0.1\",\"port\":1}]}],"
                + "\"services\":[{\"id\":\"s\",\"upstreamId\":\"u\"},{\"id\":\"d\",\"upstreamId\":\"down\"}],"
                + "\"routes\":[" + routes + "]}";
        Snapshot s = compiler.compile(ConfigDecoder.decode(json.getBytes(StandardCharsets.UTF_8)));
        HOLDER.publish(s);
        compiler.services().published(s.resources());
    }

    private static HttpResponse<String> send(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
                .timeout(Duration.ofSeconds(10));
        if (headers.length > 0) {
            b.headers(headers);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<Span> spansOf(String traceId, int expected) {
        await().atMost(Duration.ofSeconds(5)).until(() -> SPANS.stream()
                .filter(s -> s.traceId().equals(traceId)).count() >= expected);
        return SPANS.stream().filter(s -> s.traceId().equals(traceId)).toList();
    }

    private static String upstreamTraceparent(HttpResponse<String> r) {
        return new JsonObject(r.body()).getString("traceparent");
    }

    @Test
    void sampledInboundTraceIsContinuedToTheUpstream() throws Exception {
        HttpResponse<String> r = send("/ok/42", "traceparent", "00-" + TRACE + "-" + PARENT + "-01",
                "tracestate", "vendor=x");
        assertThat(r.statusCode()).isEqualTo(200);
        TraceParent sent = TraceParent.parse(upstreamTraceparent(r));
        assertThat(sent).isNotNull();
        assertThat(sent.traceId()).isEqualTo(TRACE);
        assertThat(sent.sampled()).isTrue();
        assertThat(new JsonObject(r.body()).getString("tracestate")).isEqualTo("vendor=x");

        List<Span> spans = spansOf(TRACE, 2);
        Span server = spans.stream().filter(s -> s.kind() == Span.SERVER).findFirst().orElseThrow();
        Span client = spans.stream().filter(s -> s.kind() == Span.CLIENT).findFirst().orElseThrow();
        assertThat(server.parentSpanId()).isEqualTo(PARENT);
        assertThat(client.parentSpanId()).isEqualTo(server.spanId());
        // The upstream's parent is the gateway's client span: the trace is one connected tree.
        assertThat(sent.spanId()).isEqualTo(client.spanId());
        assertThat(server.name()).isEqualTo("GET /ok/{id}");
        assertThat(server.attribute("http.route")).isEqualTo("/ok/{id}");
        assertThat(server.attribute("apigw.route.id")).isEqualTo("ok");
        assertThat(server.attribute("apigw.upstream.id")).isEqualTo("u");
        assertThat(server.attribute("http.response.status_code")).isEqualTo(200L);
        assertThat(server.failed()).isFalse();
        assertThat(client.attribute("server.address")).isEqualTo("127.0.0.1");
        assertThat(client.attribute("http.response.status_code")).isEqualTo(200L);
        assertThat(client.startEpochNanos()).isGreaterThanOrEqualTo(server.startEpochNanos());
        assertThat(client.endEpochNanos()).isLessThanOrEqualTo(server.endEpochNanos());
    }

    @Test
    void unsampledInboundContextPassesThroughUntouched() throws Exception {
        String trace = "0af7651916cd43dd8448eb211c80319c";
        String header = "00-" + trace + "-b7ad6b7169203331-00";
        HttpResponse<String> r = send("/ok/1", "traceparent", header);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(upstreamTraceparent(r)).isEqualTo(header);
        Thread.sleep(200);
        assertThat(SPANS).noneMatch(s -> s.traceId().equals(trace));
    }

    @Test
    void requestsWithoutContextStartANewTrace() throws Exception {
        HttpResponse<String> r = send("/ok/2");
        TraceParent sent = TraceParent.parse(upstreamTraceparent(r));
        assertThat(sent).isNotNull();
        assertThat(sent.sampled()).isTrue();
        List<Span> spans = spansOf(sent.traceId(), 2);
        assertThat(spans).anyMatch(s -> s.kind() == Span.SERVER && s.parentSpanId() == null);
    }

    @Test
    void failedAttemptsAreRecordedOnSpansAndMeters() throws Exception {
        HttpResponse<String> r = send("/dead", "traceparent", "00-" + "1".repeat(32) + "-" + PARENT + "-01");
        assertThat(r.statusCode()).isEqualTo(502);
        List<Span> spans = spansOf("1".repeat(32), 2);
        Span client = spans.stream().filter(s -> s.kind() == Span.CLIENT).findFirst().orElseThrow();
        Span server = spans.stream().filter(s -> s.kind() == Span.SERVER).findFirst().orElseThrow();
        assertThat(client.attribute("error.type")).isEqualTo("connect_error");
        assertThat(client.failed()).isTrue();
        assertThat(server.failed()).isTrue();
        assertThat(server.attribute("apigw.response.source")).isEqualTo("gateway");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(METRICS.scrape())
                .contains("gw_upstream_attempts_total{upstream=\"down\",outcome=\"connect_error\"} 1")
                .contains("gw_local_responses_total{route=\"dead\",code=\"502\"} 1"));
    }

    @Test
    void routeMetersCountByRouteAndStatusAndFollowTheConfig() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(send("/ok/" + i).statusCode()).isEqualTo(200);
        }
        assertThat(send("/auth").statusCode()).isEqualTo(401);
        assertThat(send("/nowhere").statusCode()).isEqualTo(404);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            String text = METRICS.scrape();
            assertThat(count(text, "gw_requests_total{route=\"ok\",code=\"2xx\"}")).isGreaterThanOrEqualTo(3);
            assertThat(text).contains("gw_requests_total{route=\"auth\",code=\"401\"} 1")
                    .contains("gw_local_responses_total{route=\"auth\",code=\"401\"} 1")
                    .contains("gw_unrouted_requests_total{code=\"404\"} 1")
                    .contains("gw_request_duration_seconds_bucket{route=\"ok\",le=\"+Inf\"}")
                    .contains("gw_upstream_response_seconds_count{upstream=\"u\"}");
            assertThat(count(text, "gw_upstream_attempts_total{upstream=\"u\",outcome=\"2xx\"}"))
                    .isGreaterThanOrEqualTo(3);
        });
        // The route is removed from the config: its series go with it.
        publish(false);
        assertThat(METRICS.scrape()).doesNotContain("route=\"auth\"").contains("route=\"ok\"");
        publish(true);
    }

    private static long count(String text, String series) {
        for (String line : text.split("\n")) {
            if (line.startsWith(series + " ")) {
                return Long.parseLong(line.substring(series.length() + 1));
            }
        }
        return 0;
    }
}
