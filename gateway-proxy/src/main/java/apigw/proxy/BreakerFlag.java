package apigw.proxy;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether one event loop's circuit breaker for an upstream is open or half-open, published for the
 * metrics scrape thread. Written only on breaker transitions.
 */
final class BreakerFlag {

    private final AtomicBoolean open = new AtomicBoolean();

    void set(boolean value) {
        open.set(value);
    }

    boolean get() {
        return open.get();
    }
}
