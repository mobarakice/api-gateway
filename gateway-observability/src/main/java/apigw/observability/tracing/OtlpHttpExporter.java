package apigw.observability.tracing;

import apigw.observability.metrics.Counter;
import apigw.observability.metrics.MetricRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exports spans to an OTLP/HTTP endpoint (JSON encoding) from one daemon thread. {@link #offer} never
 * blocks: when the bounded queue is full the span is dropped and counted. Export failures are counted
 * and logged (rate-limited); the batch is dropped, never retried, so a slow collector cannot build up
 * memory in the gateway.
 */
public final class OtlpHttpExporter implements SpanExporter, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OtlpHttpExporter.class);
    private static final long LOG_EVERY_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final BlockingQueue<Span> queue;
    private final TracingConfig config;
    private final URI uri;
    private final HttpClient http;
    private final Counter exported;
    private final Counter dropped;
    private final Counter failed;
    private final Thread worker;
    private volatile boolean closed;
    private long lastFailureLog;

    public OtlpHttpExporter(TracingConfig config, MetricRegistry metrics) {
        this.config = config;
        this.uri = config.tracesUri();
        this.queue = new ArrayBlockingQueue<>(config.queueSize());
        this.http = HttpClient.newBuilder().connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        String help = "Spans by export result: exported, dropped (queue full), failed (export error).";
        this.exported = metrics.counter("gw_tracing_spans_total", help, "result", "exported");
        this.dropped = metrics.counter("gw_tracing_spans_total", help, "result", "dropped");
        this.failed = metrics.counter("gw_tracing_spans_total", help, "result", "failed");
        metrics.gauge("gw_tracing_queue_size", "Spans waiting for export.", queue::size);
        this.worker = new Thread(this::loop, "gw-trace-exporter");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void offer(Span span) {
        if (closed || !queue.offer(span)) {
            dropped.inc();
        }
    }

    private void loop() {
        List<Span> batch = new ArrayList<>(config.batchSize());
        while (!closed || !queue.isEmpty()) {
            try {
                Span first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, config.batchSize() - 1);
                export(batch);
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void export(List<Span> batch) throws InterruptedException {
        String body = OtlpJson.encode(config.resource(), batch);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(config.timeout())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 == 2) {
                exported.add(batch.size());
                return;
            }
            failed(batch.size(), "status " + resp.statusCode());
        } catch (IOException e) {
            failed(batch.size(), e.getClass().getSimpleName());
        }
    }

    private void failed(int n, String why) {
        failed.add(n);
        long now = System.nanoTime();
        if (lastFailureLog == 0 || now - lastFailureLog > LOG_EVERY_NANOS) {
            lastFailureLog = now;
            LOG.warn("trace export failed spans={} cause={}", n, why);
        }
    }

    /** Stops accepting spans and gives queued ones up to {@code timeout} to be exported. */
    public void close(Duration timeout) {
        closed = true;
        try {
            worker.join(timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        close(config.timeout());
    }

    Map<String, String> resource() {
        return config.resource();
    }
}
