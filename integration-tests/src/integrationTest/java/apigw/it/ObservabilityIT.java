package apigw.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import apigw.testsupport.TestImages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * M8 exit checks against real backends: Prometheus (official image) scrapes the gateway process and
 * evaluates the shipped alert rules; every Grafana dashboard query runs against it; Jaeger (official
 * image) receives the gateway's OTLP spans, and the trace links the caller, the gateway's server and
 * client spans, and the upstream. Runs with the gateway as a JVM process or the native binary.
 */
class ObservabilityIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REPO = Objects.requireNonNull(Path.of(System.getProperty("user.dir")).getParent());
    private static final Path OBS = REPO.resolve("deployment/observability");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Queue<String> UPSTREAM_TRACEPARENTS = new ConcurrentLinkedQueue<>();

    private static Vertx vertx;
    private static int upstreamPort;

    @BeforeAll
    static void upstream() throws Exception {
        vertx = Vertx.vertx();
        HttpServer s = vertx.createHttpServer().requestHandler(req -> {
            String tp = req.getHeader("traceparent");
            if (tp != null) {
                UPSTREAM_TRACEPARENTS.add(tp);
            }
            req.response().putHeader("content-type", "application/json")
                    .end(new JsonObject().put("traceparent", tp).encode());
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        upstreamPort = s.actualPort();
    }

    @AfterAll
    static void stop() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void prometheusScrapesDashboardsQueryAlertsEvaluateAndTracesPropagate(@TempDir Path dir) throws Exception {
        try (GenericContainer<?> jaeger = new GenericContainer<>(DockerImageName.parse(TestImages.get("jaeger.image")))
                .withExposedPorts(4318, 16686)
                .waitingFor(Wait.forHttp("/").forPort(16686).withStartupTimeout(Duration.ofSeconds(90)))) {
            jaeger.start();
            int http = Ports.free();
            int status = Ports.free();
            Path config = Files.writeString(dir.resolve("gateway.json"), """
                    {"schemaVersion":1,"settings":{"upstreamAllowCidrs":["127.0.0.0/8"]},
                     "upstreams":[{"id":"echo","targets":[{"host":"127.0.0.1","port":UPSTREAM_PORT}]}],
                     "services":[{"id":"s","upstreamId":"echo"}],
                     "routes":[{"id":"obs","path":"/obs/{id}","match":"template","serviceId":"s"},
                               {"id":"locked","path":"/locked","serviceId":"s",
                                "plugins":[{"name":"key-auth","config":{}}]}]}
                    """.replace("UPSTREAM_PORT", Integer.toString(upstreamPort)));
            Map<String, String> env = Map.of(
                    "GW_BIND_HOST", "127.0.0.1",
                    "GW_HTTP_PORT", Integer.toString(http),
                    "GW_STATUS_PORT", Integer.toString(status),
                    "GW_EVENT_LOOPS", "2",
                    "GW_GATEWAY_ID", "gw-it",
                    "GW_CONFIG_FILE", config.toString(),
                    "GW_TRACING_ENDPOINT", "http://127.0.0.1:" + jaeger.getMappedPort(4318),
                    "GW_TRACING_SAMPLE_RATIO", "0.0",
                    "GW_TRACING_SERVICE_NAME", "apigw-gateway-it");
            try (GatewayProcess gw = GatewayProcess.start(List.of(), env)) {
                gw.awaitLine(l -> l.contains("\"msg\":\"ready revision="), Duration.ofSeconds(60));
                Testcontainers.exposeHostPorts(status);
                Files.writeString(dir.resolve("prometheus.yml"), """
                        global: {scrape_interval: 1s, evaluation_interval: 1s}
                        rule_files: [/etc/prometheus/rules/apigw-alerts.yml]
                        scrape_configs:
                          - job_name: apigw-gateway
                            static_configs: [{targets: ["host.testcontainers.internal:STATUS_PORT"]}]
                        """.replace("STATUS_PORT", Integer.toString(status)));
                try (GenericContainer<?> prom = new GenericContainer<>(
                        DockerImageName.parse(TestImages.get("prometheus.image")))
                        .withExposedPorts(9090)
                        .withAccessToHost(true)
                        .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("prometheus.yml")),
                                "/etc/prometheus/prometheus.yml")
                        .withCopyFileToContainer(MountableFile.forHostPath(OBS.resolve("prometheus/rules")),
                                "/etc/prometheus/rules")
                        .waitingFor(Wait.forHttp("/-/ready").forPort(9090))) {
                    prom.start();
                    String promUrl = "http://" + prom.getHost() + ":" + prom.getMappedPort(9090);
                    traffic(http);
                    String traceId = traced(http, jaeger);
                    prometheus(promUrl, prom);
                    System.out.printf("[%s] observability: trace %s exported and linked; prometheus scrape ok%n",
                            GatewayProcess.mode(), traceId);
                }
                assertThat(gw.output()).anyMatch(l -> l.contains("\"logger\":\"access\"")
                        && l.contains("\"routeId\":\"obs\"") && l.contains("\"traceId\":\""));
                assertThat(gw.output()).noneMatch(l -> l.contains("UnsupportedFeature")
                        || l.contains("trace export failed"));
            }
        }
    }

    private static void traffic(int http) throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(send(http, "/obs/" + i, null).statusCode()).isEqualTo(200);
        }
        assertThat(send(http, "/locked", null).statusCode()).isEqualTo(401);
        assertThat(send(http, "/missing", null).statusCode()).isEqualTo(404);
    }

    /** A sampled caller (ratio 0: only the parent's decision records it); returns the trace ID. */
    private static String traced(int http, GenericContainer<?> jaeger) throws Exception {
        String traceId = "5e1f0c0ffee0" + Long.toHexString(System.nanoTime() | (1L << 60)).substring(0, 16) + "abcd";
        String callerSpan = "a1b2c3d4e5f60718";
        HttpResponse<String> r = send(http, "/obs/traced", "00-" + traceId + "-" + callerSpan + "-01");
        assertThat(r.statusCode()).isEqualTo(200);
        String upstreamTp = JSON.readTree(r.body()).path("traceparent").asText();
        assertThat(upstreamTp).startsWith("00-" + traceId + "-").endsWith("-01");
        String upstreamParent = upstreamTp.substring(36, 52);
        // Unsampled requests (ratio 0, no parent) carry no gateway-made traceparent upstream.
        assertThat(UPSTREAM_TRACEPARENTS).allMatch(tp -> tp.contains(traceId));

        String url = "http://" + jaeger.getHost() + ":" + jaeger.getMappedPort(16686) + "/api/traces/" + traceId;
        JsonNode[] trace = new JsonNode[1];
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).until(() -> {
            HttpResponse<String> q = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (q.statusCode() != 200) {
                return false;
            }
            JsonNode data = JSON.readTree(q.body()).path("data");
            if (data.size() == 1 && data.get(0).path("spans").size() >= 2) {
                trace[0] = data.get(0);
                return true;
            }
            return false;
        });
        JsonNode t = trace[0];
        JsonNode server = null;
        JsonNode client = null;
        for (JsonNode span : t.path("spans")) {
            String kind = tag(span, "span.kind");
            if ("server".equals(kind)) {
                server = span;
            } else if ("client".equals(kind)) {
                client = span;
            }
        }
        assertThat(server).as("server span").isNotNull();
        assertThat(client).as("client span").isNotNull();
        assertThat(server.path("operationName").asText()).isEqualTo("GET /obs/{id}");
        assertThat(parent(server)).isEqualTo(callerSpan);
        assertThat(parent(client)).isEqualTo(server.path("spanID").asText());
        assertThat(client.path("spanID").asText()).isEqualTo(upstreamParent);
        assertThat(tag(server, "http.route")).isEqualTo("/obs/{id}");
        assertThat(tag(server, "apigw.route.id")).isEqualTo("obs");
        // Jaeger's v1 query API maps the current semantic-convention name back to http.status_code.
        String status = tag(server, "http.response.status_code");
        assertThat(status != null ? status : tag(server, "http.status_code")).as(server.toString()).isEqualTo("200");
        String processId = server.path("processID").asText();
        assertThat(t.path("processes").path(processId).path("serviceName").asText()).isEqualTo("apigw-gateway-it");
        return traceId;
    }

    private static void prometheus(String url, GenericContainer<?> prom) throws Exception {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1))
                .until(() -> value(url, "sum(gw_requests_total{job=\"apigw-gateway\",route=\"obs\"})") >= 21);
        assertThat(value(url, "up{job=\"apigw-gateway\"}")).isEqualTo(1);
        assertThat(value(url, "gw_requests_total{route=\"locked\",code=\"401\"}")).isEqualTo(1);
        assertThat(value(url, "gw_unrouted_requests_total{code=\"404\"}")).isEqualTo(1);
        assertThat(value(url, "gw_upstream_attempts_total{upstream=\"echo\",outcome=\"2xx\"}")).isEqualTo(21);
        assertThat(value(url, "gw_ready")).isEqualTo(1);
        assertThat(value(url, "count(apigw_build_info{component=\"gateway\"})")).isEqualTo(1);
        assertThat(value(url, "count(process_cpu_seconds_total)")).isEqualTo(1);

        // Alert rules: loaded, and every rule evaluates without error against real series.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            JsonNode groups = get(url + "/api/v1/rules").path("data").path("groups");
            int rules = 0;
            for (JsonNode g : groups) {
                for (JsonNode rule : g.path("rules")) {
                    rules++;
                    assertThat(rule.path("health").asText()).as(rule.path("name").asText()).isEqualTo("ok");
                }
            }
            assertThat(rules).isEqualTo(24);
        });
        assertThat(get(url + "/api/v1/alerts").path("data").path("alerts").toString())
                .doesNotContain("GatewayDown").doesNotContain("GatewayNotReady");
        Container.ExecResult tests = prom.execInContainer("sh", "-c",
                "cd /etc/prometheus/rules && promtool test rules apigw-alerts.test.yml");
        assertThat(tests.getExitCode()).as(tests.getStdout() + tests.getStderr()).isZero();

        // Dashboard: every query parses and runs; the traffic panels have data.
        JsonNode dashboard = JSON.readTree(OBS.resolve("grafana/dashboards/apigw-gateway.json").toFile());
        List<String> failed = new ArrayList<>();
        int queries = 0;
        int withData = 0;
        for (JsonNode panel : dashboard.path("panels")) {
            for (JsonNode target : panel.path("targets")) {
                String expr = target.path("expr").asText().replace("$__rate_interval", "1m")
                        .replace("$__range", "5m").replace("$instance", ".*").replace("$route", ".*");
                JsonNode res = get(url + "/api/v1/query?query=" + URLEncoder.encode(expr, StandardCharsets.UTF_8));
                queries++;
                if (!"success".equals(res.path("status").asText())) {
                    failed.add(panel.path("title").asText() + ": " + res.path("error").asText());
                } else if (!res.path("data").path("result").isEmpty()) {
                    withData++;
                }
            }
        }
        assertThat(failed).isEmpty();
        System.out.printf("[%s] dashboard queries=%d withData=%d%n", GatewayProcess.mode(), queries, withData);
        assertThat(withData).isGreaterThanOrEqualTo(10);
    }

    private static double value(String url, String query) throws Exception {
        JsonNode r = get(url + "/api/v1/query?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        JsonNode result = r.path("data").path("result");
        return result.isEmpty() ? -1 : Double.parseDouble(result.get(0).path("value").get(1).asText());
    }

    private static JsonNode get(String url) throws Exception {
        return JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }

    private static HttpResponse<String> send(int port, String path, String traceparent) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (traceparent != null) {
            b.header("traceparent", traceparent);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String tag(JsonNode span, String key) {
        for (JsonNode tag : span.path("tags")) {
            if (key.equals(tag.path("key").asText())) {
                return tag.path("value").asText();
            }
        }
        return null;
    }

    private static String parent(JsonNode span) {
        for (JsonNode ref : span.path("references")) {
            if ("CHILD_OF".equals(ref.path("refType").asText())) {
                return ref.path("spanID").asText();
            }
        }
        return null;
    }
}
