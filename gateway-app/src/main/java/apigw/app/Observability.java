package apigw.app;

import apigw.core.observe.RequestTelemetry;
import apigw.observability.metrics.MetricRegistry;

/** The process's metrics registry and request telemetry, wired once in {@link GatewayMain}. */
record Observability(MetricRegistry metrics, RequestTelemetry requests) {

    /** A private registry and no request telemetry (tests). */
    static Observability none() {
        return new Observability(new MetricRegistry(), RequestTelemetry.NONE);
    }
}
