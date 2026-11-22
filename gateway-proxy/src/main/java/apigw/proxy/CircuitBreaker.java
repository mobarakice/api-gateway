package apigw.proxy;

import apigw.model.Upstream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closed / open / half-open breaker for one upstream on one event loop (ADR 0017), so it needs no
 * synchronisation. The window is {@code windowSeconds} one-second buckets; totals are kept
 * incrementally (stale buckets are subtracted as time advances), so recording is O(1) amortised.
 *
 * <p>{@link #tryAcquire} hands out a permit kind; the caller reports the result with that permit, so a
 * call admitted while closed never counts as a half-open trial after the state changed.
 */
final class CircuitBreaker {

    private static final Logger LOG = LoggerFactory.getLogger(CircuitBreaker.class);

    static final int REJECTED = 0;
    static final int CLOSED_PERMIT = 1;
    static final int TRIAL_PERMIT = 2;

    enum State { CLOSED, OPEN, HALF_OPEN }

    private final String upstreamId;
    private final Upstream.CircuitBreaker spec;
    private final int[] calls;
    private final int[] failures;
    private final int[] slow;
    private long lastSecond = Long.MIN_VALUE;
    private int totalCalls;
    private int totalFailures;
    private int totalSlow;
    private State state = State.CLOSED;
    /** Told about every transition (true = open or half-open), e.g. to publish the state to metrics. */
    private final java.util.function.Consumer<Boolean> onChange;
    private long openUntilMs;
    private int trialsInFlight;
    private int trialsSucceeded;

    CircuitBreaker(String upstreamId, Upstream.CircuitBreaker spec) {
        this(upstreamId, spec, open -> { });
    }

    CircuitBreaker(String upstreamId, Upstream.CircuitBreaker spec, java.util.function.Consumer<Boolean> onChange) {
        this.onChange = onChange;
        this.upstreamId = upstreamId;
        this.spec = spec;
        this.calls = new int[spec.windowSeconds()];
        this.failures = new int[spec.windowSeconds()];
        this.slow = new int[spec.windowSeconds()];
    }

    State state() {
        return state;
    }

    boolean isFailureStatus(int status) {
        return spec.failureStatuses().contains(status);
    }

    boolean isSlow(long elapsedMs) {
        return spec.slowCallMs() > 0 && elapsedMs >= spec.slowCallMs();
    }

    int tryAcquire(long nowMs) {
        if (state == State.CLOSED) {
            return CLOSED_PERMIT;
        }
        if (state == State.OPEN) {
            if (nowMs < openUntilMs) {
                return REJECTED;
            }
            transition(State.HALF_OPEN, nowMs);
        }
        if (trialsInFlight + trialsSucceeded < spec.halfOpenCalls()) {
            trialsInFlight++;
            return TRIAL_PERMIT;
        }
        return REJECTED;
    }

    /** A permit ended without a result (client left, request cancelled). */
    void release(int permit) {
        if (permit == TRIAL_PERMIT && state == State.HALF_OPEN) {
            trialsInFlight--;
        }
    }

    void onResult(int permit, boolean failure, boolean slowCall, long nowMs) {
        if (permit == TRIAL_PERMIT) {
            if (state != State.HALF_OPEN) {
                return;
            }
            trialsInFlight--;
            if (failure || slowCall) {
                transition(State.OPEN, nowMs);
            } else if (++trialsSucceeded >= spec.halfOpenCalls()) {
                transition(State.CLOSED, nowMs);
            }
            return;
        }
        if (permit != CLOSED_PERMIT || state != State.CLOSED) {
            return;
        }
        int b = advance(nowMs / 1000);
        calls[b]++;
        totalCalls++;
        if (failure) {
            failures[b]++;
            totalFailures++;
        }
        if (slowCall) {
            slow[b]++;
            totalSlow++;
        }
        if (totalCalls >= spec.minCalls()
                && (totalFailures * 100L >= (long) spec.failureRatePercent() * totalCalls
                        || spec.slowCallMs() > 0
                                && totalSlow * 100L >= (long) spec.slowCallRatePercent() * totalCalls)) {
            transition(State.OPEN, nowMs);
        }
    }

    /** Moves the window to {@code second}, clearing buckets that fell out; returns the bucket index. */
    private int advance(long second) {
        int n = calls.length;
        if (lastSecond == Long.MIN_VALUE || second - lastSecond >= n) {
            reset();
        } else {
            for (long s = lastSecond + 1; s <= second; s++) {
                clear(Math.floorMod(s, n));
            }
        }
        lastSecond = Math.max(lastSecond, second);
        return Math.floorMod(second, n);
    }

    private void clear(int b) {
        totalCalls -= calls[b];
        totalFailures -= failures[b];
        totalSlow -= slow[b];
        calls[b] = 0;
        failures[b] = 0;
        slow[b] = 0;
    }

    private void reset() {
        java.util.Arrays.fill(calls, 0);
        java.util.Arrays.fill(failures, 0);
        java.util.Arrays.fill(slow, 0);
        totalCalls = 0;
        totalFailures = 0;
        totalSlow = 0;
    }

    private void transition(State to, long nowMs) {
        onChange.accept(to != State.CLOSED);
        State from = state;
        state = to;
        trialsInFlight = 0;
        trialsSucceeded = 0;
        if (to == State.OPEN) {
            openUntilMs = nowMs + spec.openMs();
            LOG.warn("circuit breaker opened upstream={} from={} calls={} failures={} slow={} openMs={}", upstreamId,
                    from, totalCalls, totalFailures, totalSlow, spec.openMs());
        } else {
            LOG.info("circuit breaker state change upstream={} from={} to={}", upstreamId, from, to);
        }
        if (to != State.HALF_OPEN) {
            reset();
            lastSecond = Long.MIN_VALUE;
        }
    }
}
