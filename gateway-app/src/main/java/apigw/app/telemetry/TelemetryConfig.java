package apigw.app.telemetry;

import apigw.observability.tracing.TracingConfig;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Telemetry settings from the environment.
 *
 * <pre>
 *   GW_ACCESS_LOG                 all (default) | errors (status &gt;= 500 or gateway-generated 4xx/5xx) | off
 *   GW_TRACING_ENDPOINT           OTLP/HTTP base URL, e.g. http://otel-collector:4318; unset = tracing off
 *   GW_TRACING_SAMPLE_RATIO       0..1, share of new traces recorded (default 0.01)
 *   GW_TRACING_RESPECT_PARENT     true (default): follow an inbound traceparent's sampled flag
 *   GW_TRACING_SERVICE_NAME       service.name resource attribute (default apigw-gateway)
 *   GW_TRACING_QUEUE_SIZE         spans buffered for export (default 8192)
 * </pre>
 */
public record TelemetryConfig(AccessLogMode accessLog, Optional<TracingConfig> tracing) {

    /** Which finished requests get an access log line. */
    public enum AccessLogMode { ALL, ERRORS, OFF }

    public static TelemetryConfig fromEnvironment(Map<String, String> env, String gatewayId, String version) {
        String mode = env.getOrDefault("GW_ACCESS_LOG", "all").trim().toUpperCase(Locale.ROOT);
        AccessLogMode access;
        try {
            access = AccessLogMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("GW_ACCESS_LOG must be all, errors or off", e);
        }
        String endpoint = env.get("GW_TRACING_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            return new TelemetryConfig(access, Optional.empty());
        }
        double ratio = number(env, "GW_TRACING_SAMPLE_RATIO", "0.01");
        String respect = env.getOrDefault("GW_TRACING_RESPECT_PARENT", "true").trim();
        if (!"true".equals(respect) && !"false".equals(respect)) {
            throw new IllegalArgumentException("GW_TRACING_RESPECT_PARENT must be true or false");
        }
        int queue = (int) number(env, "GW_TRACING_QUEUE_SIZE", "8192");
        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("GW_TRACING_ENDPOINT is not a URL", e);
        }
        Map<String, String> resource = Map.of(
                "service.name", env.getOrDefault("GW_TRACING_SERVICE_NAME", "apigw-gateway").trim(),
                "service.version", version,
                "service.instance.id", gatewayId);
        return new TelemetryConfig(access, Optional.of(new TracingConfig(uri, ratio, Boolean.parseBoolean(respect),
                resource, queue, Math.min(512, queue), Duration.ofSeconds(10))));
    }

    private static double number(Map<String, String> env, String name, String def) {
        String v = env.getOrDefault(name, def).trim();
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number", e);
        }
    }
}
