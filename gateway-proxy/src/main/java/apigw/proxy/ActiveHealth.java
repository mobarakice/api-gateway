package apigw.proxy;

import java.util.concurrent.atomic.AtomicIntegerArray;

/** Active-probe verdict per target, written by the {@link HealthChecker}, read by every event loop. */
public final class ActiveHealth {

    private final AtomicIntegerArray healthy;

    ActiveHealth(int targets) {
        healthy = new AtomicIntegerArray(targets);
        for (int i = 0; i < targets; i++) {
            // Healthy until proven otherwise: a cold start must not black-hole traffic.
            healthy.set(i, 1);
        }
    }

    public boolean healthy(int target) {
        return healthy.get(target) == 1;
    }

    void set(int target, boolean value) {
        healthy.set(target, value ? 1 : 0);
    }
}
