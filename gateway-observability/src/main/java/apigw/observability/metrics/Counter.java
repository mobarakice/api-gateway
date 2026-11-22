package apigw.observability.metrics;

import java.util.concurrent.atomic.LongAdder;

/** A monotonically increasing count. Thread-safe; {@link #inc()} does not allocate. */
public final class Counter {

    private final LongAdder value = new LongAdder();

    Counter() {
    }

    public void inc() {
        value.increment();
    }

    public void add(long n) {
        if (n > 0) {
            value.add(n);
        }
    }

    public long get() {
        return value.sum();
    }
}
