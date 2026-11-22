package apigw.app;

import apigw.observability.metrics.MetricRegistry;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

/**
 * Probe endpoints on the internal status port: {@code /healthz} (process is alive and serving) and
 * {@code /readyz} (a valid snapshot is loaded and the gateway is not draining), and {@code /configz}
 * (config source state: revision, last error), and {@code /metrics} (Prometheus text). Internal port only.
 */
final class StatusHandler implements Handler<HttpServerRequest> {

    private static final CharSequence APPLICATION_JSON = HttpHeaders.createOptimized("application/json");
    private static final Buffer UP = Buffer.buffer("{\"status\":\"UP\"}");
    private static final Buffer READY = Buffer.buffer("{\"status\":\"READY\"}");

    private static final CharSequence PROMETHEUS_TEXT =
            HttpHeaders.createOptimized("text/plain; version=0.0.4; charset=utf-8");

    private final Readiness readiness;
    private final MetricRegistry metrics;
    private final Vertx vertx;

    StatusHandler(Readiness readiness, MetricRegistry metrics, Vertx vertx) {
        this.readiness = readiness;
        this.metrics = metrics;
        this.vertx = vertx;
    }

    @Override
    public void handle(HttpServerRequest request) {
        if ("/metrics".equals(request.path())) {
            // Rendering reads /proc and walks every series: off the event loop.
            vertx.executeBlocking(metrics::scrape, false).onComplete(ar -> {
                if (ar.succeeded()) {
                    request.response().putHeader(HttpHeaders.CONTENT_TYPE, PROMETHEUS_TEXT).end(ar.result());
                } else {
                    request.response().setStatusCode(500).end();
                }
            });
            return;
        }
        HttpServerResponse response = request.response().putHeader(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON);
        switch (request.path()) {
            case "/healthz" -> response.end(UP);
            case "/configz" -> response.end(readiness.configStatus());
            case "/readyz" -> {
                String reason = readiness.reason();
                if (reason == null) {
                    response.end(READY);
                } else {
                    // reason is one of a few fixed internal strings; escape defensively anyway.
                    response.setStatusCode(503).end("{\"status\":\"NOT_READY\",\"reason\":\""
                            + reason.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
                }
            }
            default -> response.setStatusCode(404).end("{\"status\":\"NOT_FOUND\"}");
        }
    }
}
