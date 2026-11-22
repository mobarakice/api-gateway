package apigw.observability.tracing;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Tracing settings.
 *
 * @param endpoint      OTLP/HTTP base URI (the exporter posts to {@code /v1/traces})
 * @param ratio         share of new traces sampled, 0..1
 * @param respectParent follow an inbound {@code traceparent} sampled flag instead of the ratio
 * @param resource      resource attributes (service.name, service.version, service.instance.id, ...)
 * @param queueSize     spans buffered for export; more are dropped and counted
 * @param batchSize     spans per export request
 * @param timeout       per export request
 */
public record TracingConfig(URI endpoint, double ratio, boolean respectParent, Map<String, String> resource,
        int queueSize, int batchSize, Duration timeout) {

    public TracingConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        String scheme = endpoint.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme) || endpoint.getHost() == null) {
            throw new IllegalArgumentException("tracing endpoint must be an http(s) URL");
        }
        if (endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException("tracing endpoint must not contain credentials");
        }
        if (!(ratio >= 0 && ratio <= 1)) {
            throw new IllegalArgumentException("sample ratio must be between 0 and 1");
        }
        if (queueSize < 1 || batchSize < 1 || batchSize > queueSize) {
            throw new IllegalArgumentException("queue and batch sizes must be positive, batch <= queue");
        }
        resource = Map.copyOf(resource);
    }

    /** The traces endpoint: {@code {base}/v1/traces}. */
    public URI tracesUri() {
        String base = endpoint.toString();
        return URI.create(base.endsWith("/") ? base + "v1/traces" : base + "/v1/traces");
    }
}
