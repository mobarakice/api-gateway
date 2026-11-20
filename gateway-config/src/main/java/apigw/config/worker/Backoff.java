package apigw.config.worker;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with equal jitter: attempt n waits between d/2 and d, where
 * d = min(max, base * 2^n). Jitter keeps a fleet of gateways from retrying in lockstep after an
 * etcd outage.
 */
final class Backoff {

    private final long baseMillis;
    private final long maxMillis;

    Backoff(Duration base, Duration max) {
        this.baseMillis = base.toMillis();
        this.maxMillis = max.toMillis();
    }

    long delayMillis(int attempt) {
        long d = maxMillis;
        if (attempt < 30) {
            d = Math.min(maxMillis, baseMillis << attempt);
        }
        long half = Math.max(1, d / 2);
        return half + ThreadLocalRandom.current().nextLong(half + 1);
    }
}
