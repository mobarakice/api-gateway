package apigw.app;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

/**
 * Probe endpoints on the internal status port: {@code /healthz} (process is alive and serving) and
 * {@code /readyz} (a valid snapshot is loaded and the gateway is not draining), and {@code /configz}
 * (config source state: revision, last error). Internal port only.
 */
final class StatusHandler implements Handler<HttpServerRequest> {

    private static final CharSequence APPLICATION_JSON = HttpHeaders.createOptimized("application/json");
    private static final Buffer UP = Buffer.buffer("{\"status\":\"UP\"}");
    private static final Buffer READY = Buffer.buffer("{\"status\":\"READY\"}");

    private final Readiness readiness;

    StatusHandler(Readiness readiness) {
        this.readiness = readiness;
    }

    @Override
    public void handle(HttpServerRequest request) {
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
