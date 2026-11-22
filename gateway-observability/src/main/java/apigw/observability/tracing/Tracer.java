package apigw.observability.tracing;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Sampling decisions and span lifecycle. Unsampled requests allocate nothing here; an inbound
 * {@code traceparent} of an unsampled request is forwarded untouched by the caller.
 */
public final class Tracer {

    private final TracingConfig config;
    private final SpanExporter exporter;
    private volatile Anchor anchor = Anchor.now();

    public Tracer(TracingConfig config, SpanExporter exporter) {
        this.config = config;
        this.exporter = exporter;
    }

    /** Whether a request with this inbound context (null = none) is recorded. */
    public boolean sample(TraceParent inbound) {
        if (inbound != null && config.respectParent()) {
            return inbound.sampled();
        }
        double ratio = config.ratio();
        return ratio >= 1 || ratio > 0 && ThreadLocalRandom.current().nextDouble() < ratio;
    }

    /** Starts the server span: continues {@code inbound}'s trace, or a new one when null. */
    public Span server(TraceParent inbound, String name, long startNanoTime) {
        String traceId = inbound == null ? TraceIds.traceId() : inbound.traceId();
        String parent = inbound == null ? null : inbound.spanId();
        return new Span(traceId, TraceIds.spanId(), parent, Span.SERVER, name, epochNanos(startNanoTime));
    }

    /** Starts a client span under {@code parent}. */
    public Span client(Span parent, String name, long startNanoTime) {
        return new Span(parent.traceId, TraceIds.spanId(), parent.spanId, Span.CLIENT, name,
                epochNanos(startNanoTime));
    }

    /** Ends {@code span} (once) and queues it for export. */
    public void end(Span span, long endNanoTime) {
        if (span.ended()) {
            return;
        }
        span.endEpochNanos = Math.max(span.startEpochNanos + 1, epochNanos(endNanoTime));
        exporter.offer(span);
    }

    /** Wall-clock nanoseconds for a {@link System#nanoTime()} reading; re-anchored every minute. */
    long epochNanos(long nanoTime) {
        Anchor a = anchor;
        if (nanoTime - a.nano > 60_000_000_000L) {
            a = Anchor.now();
            anchor = a;
        }
        return a.epochNanos + (nanoTime - a.nano);
    }

    private record Anchor(long epochNanos, long nano) {
        static Anchor now() {
            java.time.Instant i = java.time.Instant.now();
            long nano = System.nanoTime();
            return new Anchor(i.getEpochSecond() * 1_000_000_000L + i.getNano(), nano);
        }
    }
}
