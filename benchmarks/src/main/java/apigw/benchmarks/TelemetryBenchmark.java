package apigw.benchmarks;

import apigw.app.telemetry.GatewayTelemetry;
import apigw.app.telemetry.TelemetryConfig;
import apigw.core.observe.AttemptOutcome;
import apigw.core.observe.RouteTelemetry;
import apigw.observability.metrics.MetricRegistry;
import apigw.observability.tracing.TraceParent;
import apigw.observability.tracing.Tracer;
import apigw.observability.tracing.TracingConfig;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Per-request telemetry cost (ADR 0023): the route meters recorded on completion and per upstream attempt, and the
 * tracing decision for an unsampled request (parse the inbound traceparent, sample). Run with {@code -prof gc}:
 * the meters allocate nothing. {@code @Threads(4)} shows LongAdder contention across event loops.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class TelemetryBenchmark {

    private static final String UNSAMPLED = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00";

    private RouteTelemetry route;
    private Tracer tracer;
    private long t;

    @Setup
    public void setUp() {
        MetricRegistry registry = new MetricRegistry();
        tracer = new Tracer(new TracingConfig(URI.create("http://127.0.0.1:4318"), 0.0, true,
                Map.of("service.name", "bench"), 1024, 128, Duration.ofSeconds(1)), span -> { });
        route = new GatewayTelemetry(registry, TelemetryConfig.AccessLogMode.OFF, tracer)
                .forRoute("orders", "orders-up");
        // Create the 2xx series up front, as a warmed-up gateway would have.
        route.completed(200, false, 1_000);
        route.attempt(AttemptOutcome.RESPONSE, 200, 1_000, 0);
    }

    @Benchmark
    @Threads(1)
    public void routeMetersPerRequest() {
        long d = (t += 997) & 0xFFFFFF;
        route.attempt(AttemptOutcome.RESPONSE, 200, d, 0);
        route.completed(200, false, d);
    }

    @Benchmark
    @Threads(4)
    public void routeMetersPerRequestContended() {
        route.attempt(AttemptOutcome.RESPONSE, 200, 250_000, 0);
        route.completed(200, false, 400_000);
    }

    @Benchmark
    public boolean unsampledTraceDecision() {
        return tracer.sample(TraceParent.parse(UNSAMPLED));
    }

    @Benchmark
    public boolean noParentTraceDecision() {
        return tracer.sample(null);
    }
}
