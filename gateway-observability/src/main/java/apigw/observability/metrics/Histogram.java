package apigw.observability.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * Durations in fixed buckets (seconds in the exposition, nanoseconds when recording). Thread-safe;
 * {@link #observeNanos} does not allocate: a linear scan over a handful of bounds and two adds.
 */
public final class Histogram {

    private final double[] boundsSeconds;
    private final long[] boundsNanos;
    /** Non-cumulative counts per bucket; the last slot is +Inf. */
    private final LongAdder[] buckets;
    private final LongAdder sumNanos = new LongAdder();

    Histogram(double[] boundsSeconds) {
        this.boundsSeconds = boundsSeconds.clone();
        this.boundsNanos = new long[boundsSeconds.length];
        for (int i = 0; i < boundsSeconds.length; i++) {
            boundsNanos[i] = Math.round(boundsSeconds[i] * 1e9);
        }
        this.buckets = new LongAdder[boundsSeconds.length + 1];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
    }

    public void observeNanos(long nanos) {
        long v = Math.max(0, nanos);
        long[] b = boundsNanos;
        int i = 0;
        while (i < b.length && v > b[i]) {
            i++;
        }
        buckets[i].increment();
        sumNanos.add(v);
    }

    double[] bounds() {
        return boundsSeconds;
    }

    /** Cumulative counts per bound, then the total (the +Inf bucket). */
    long[] cumulative() {
        long[] out = new long[buckets.length];
        long running = 0;
        for (int i = 0; i < buckets.length; i++) {
            running += buckets[i].sum();
            out[i] = running;
        }
        return out;
    }

    double sumSeconds() {
        return sumNanos.sum() / 1e9;
    }

    /** Total observations. */
    public long count() {
        long n = 0;
        for (LongAdder b : buckets) {
            n += b.sum();
        }
        return n;
    }
}
