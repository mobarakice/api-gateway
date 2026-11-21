package apigw.plugins.ratelimit;

/**
 * Limit algorithms (ADR 0018). Local state objects are shared by all event loops of the process and
 * guarded by their own monitor: the critical section is a few arithmetic operations, never I/O.
 *
 * <p>{@link State#take} returns {@code >= 0} (allowed; the value is the remaining quota) or
 * {@code < 0} (denied; {@code -value} is the retry-after in milliseconds, at least 1).
 */
enum Algorithm {
    TOKEN_BUCKET, FIXED_WINDOW, SLIDING_WINDOW;

    /** Per-key mutable state. */
    abstract static class State {
        abstract long take(long nowMs);

        /** Milliseconds until the quota is fully restored (for the {@code RateLimit} header's {@code t}). */
        abstract long resetMs(long nowMs);
    }

    State newState(Limits l, long nowMs) {
        return switch (this) {
            case TOKEN_BUCKET -> new Bucket(l, nowMs);
            case FIXED_WINDOW -> new Fixed(l);
            case SLIDING_WINDOW -> new Sliding(l);
        };
    }

    /** {@code limit} requests per {@code windowMs}; {@code burst} is the token bucket capacity. */
    record Limits(long limit, long windowMs, long burst) {
        double ratePerMs() {
            return (double) limit / windowMs;
        }
    }

    static final class Bucket extends State {
        private final Limits l;
        private double tokens;
        private long last;

        Bucket(Limits l, long nowMs) {
            this.l = l;
            this.tokens = l.burst();
            this.last = nowMs;
        }

        @Override
        synchronized long take(long nowMs) {
            refill(nowMs);
            if (tokens >= 1) {
                tokens -= 1;
                return (long) tokens;
            }
            return -Math.max(1, (long) Math.ceil((1 - tokens) / l.ratePerMs()));
        }

        @Override
        synchronized long resetMs(long nowMs) {
            refill(nowMs);
            return (long) Math.ceil((l.burst() - tokens) / l.ratePerMs());
        }

        private void refill(long nowMs) {
            if (nowMs > last) {
                tokens = Math.min(l.burst(), tokens + (nowMs - last) * l.ratePerMs());
                last = nowMs;
            }
        }
    }

    static final class Fixed extends State {
        private final Limits l;
        private long window = Long.MIN_VALUE;
        private long used;

        Fixed(Limits l) {
            this.l = l;
        }

        @Override
        synchronized long take(long nowMs) {
            roll(nowMs);
            if (used < l.limit()) {
                used++;
                return l.limit() - used;
            }
            return -Math.max(1, (window + 1) * l.windowMs() - nowMs);
        }

        @Override
        synchronized long resetMs(long nowMs) {
            roll(nowMs);
            return (window + 1) * l.windowMs() - nowMs;
        }

        private void roll(long nowMs) {
            long w = Math.floorDiv(nowMs, l.windowMs());
            if (w != window) {
                window = w;
                used = 0;
            }
        }
    }

    /** Sliding window counter: previous window's count weighted by its remaining overlap, plus this one's. */
    static final class Sliding extends State {
        private final Limits l;
        private long window = Long.MIN_VALUE;
        private long prev;
        private long cur;

        Sliding(Limits l) {
            this.l = l;
        }

        @Override
        synchronized long take(long nowMs) {
            roll(nowMs);
            long elapsed = nowMs - window * l.windowMs();
            double used = prev * ((double) (l.windowMs() - elapsed) / l.windowMs()) + cur;
            if (used + 1 <= l.limit()) {
                cur++;
                return (long) Math.floor(l.limit() - used - 1);
            }
            long untilWindow = l.windowMs() - elapsed;
            if (prev == 0) {
                return -Math.max(1, untilWindow);
            }
            // The previous window's weight decays linearly; find when one slot frees up.
            double needDrop = used + 1 - l.limit();
            long decay = (long) Math.ceil(needDrop / prev * l.windowMs());
            // A hint: after the window rolls, this window's count becomes the decaying previous one.
            return -Math.max(1, Math.min(decay, untilWindow));
        }

        @Override
        synchronized long resetMs(long nowMs) {
            roll(nowMs);
            long elapsed = nowMs - window * l.windowMs();
            return 2 * l.windowMs() - elapsed;
        }

        private void roll(long nowMs) {
            long w = Math.floorDiv(nowMs, l.windowMs());
            if (w == window) {
                return;
            }
            prev = w == window + 1 ? cur : 0;
            cur = 0;
            window = w;
        }
    }
}
