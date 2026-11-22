package apigw.observability.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import apigw.observability.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OtlpHttpExporterTest {

    private HttpServer collector;
    private final BlockingQueue<Request> received = new LinkedBlockingQueue<>();
    private final AtomicInteger status = new AtomicInteger(200);

    private record Request(String path, String contentType, String body) {
    }

    @BeforeEach
    void startCollector() throws IOException {
        collector = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        collector.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Request(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("content-type"), new String(body,
                            java.nio.charset.StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        collector.start();
    }

    @AfterEach
    void stop() {
        collector.stop(0);
    }

    private TracingConfig config(int queue) {
        return new TracingConfig(URI.create("http://127.0.0.1:" + collector.getAddress().getPort()), 1.0, true,
                Map.of("service.name", "apigw-gateway", "service.instance.id", "gw-1"), queue, Math.min(4, queue),
                Duration.ofSeconds(2));
    }

    @Test
    void exportsOtlpJsonToV1Traces() throws Exception {
        MetricRegistry metrics = new MetricRegistry();
        OtlpHttpExporter exporter = new OtlpHttpExporter(config(64), metrics);
        Tracer tracer = new Tracer(config(64), exporter);
        long now = System.nanoTime();
        Span server = tracer.server(null, "GET /orders/{id}", now)
                .attr("http.request.method", "GET").attr("http.response.status_code", 502).error();
        Span client = tracer.client(server, "GET", now + 10).attr("server.address", "orders \"a\"");
        tracer.end(client, now + 100);
        tracer.end(server, now + 200);
        exporter.close(Duration.ofSeconds(5));

        Request req = received.poll(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.path()).isEqualTo("/v1/traces");
        assertThat(req.contentType()).isEqualTo("application/json");
        JsonNode root = new ObjectMapper().readTree(req.body());
        JsonNode rs = root.path("resourceSpans").get(0);
        assertThat(rs.path("resource").path("attributes").toString())
                .contains("{\"key\":\"service.name\",\"value\":{\"stringValue\":\"apigw-gateway\"}}");
        JsonNode spans = rs.path("scopeSpans").get(0).path("spans");
        assertThat(spans).hasSize(2);
        JsonNode c = spans.get(0);
        JsonNode s = spans.get(1);
        assertThat(c.path("kind").asInt()).isEqualTo(3);
        assertThat(s.path("kind").asInt()).isEqualTo(2);
        assertThat(c.path("traceId").asText()).isEqualTo(s.path("traceId").asText()).matches("[0-9a-f]{32}");
        assertThat(c.path("parentSpanId").asText()).isEqualTo(s.path("spanId").asText());
        assertThat(s.has("parentSpanId")).isFalse();
        assertThat(s.path("name").asText()).isEqualTo("GET /orders/{id}");
        assertThat(s.path("status").path("code").asInt()).isEqualTo(2);
        assertThat(Long.parseLong(s.path("endTimeUnixNano").asText()) - Long.parseLong(
                s.path("startTimeUnixNano").asText())).isEqualTo(200);
        assertThat(s.path("attributes").toString())
                .contains("{\"key\":\"http.response.status_code\",\"value\":{\"intValue\":\"502\"}}");
        assertThat(c.path("attributes").toString()).contains("orders \\\"a\\\"");
        assertThat(metrics.scrape()).contains("gw_tracing_spans_total{result=\"exported\"} 2");
    }

    @Test
    void dropsWhenTheQueueIsFullAndCountsFailures() throws Exception {
        status.set(503);
        MetricRegistry metrics = new MetricRegistry();
        OtlpHttpExporter exporter = new OtlpHttpExporter(config(1), metrics);
        Tracer tracer = new Tracer(config(1), exporter);
        long now = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            tracer.end(tracer.server(null, "GET", now), now + 1);
        }
        exporter.close(Duration.ofSeconds(5));
        String text = metrics.scrape();
        long dropped = value(text, "gw_tracing_spans_total{result=\"dropped\"}");
        long failed = value(text, "gw_tracing_spans_total{result=\"failed\"}");
        assertThat(dropped).isPositive();
        assertThat(dropped + failed).isEqualTo(200);
        assertThat(value(text, "gw_tracing_spans_total{result=\"exported\"}")).isZero();
    }

    private static long value(String text, String series) {
        for (String line : text.split("\n")) {
            if (line.startsWith(series + " ")) {
                return Long.parseLong(line.substring(series.length() + 1));
            }
        }
        return 0;
    }
}
