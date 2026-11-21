package apigw.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import apigw.model.Upstream;
import java.util.List;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    private static CircuitBreaker breaker(int window, int minCalls, int ratePercent, int openMs, int trials) {
        return new CircuitBreaker("u", new Upstream.CircuitBreaker(window, minCalls, ratePercent, 0, 100, openMs,
                trials, List.of(503)));
    }

    @Test
    void opensOnFailureRateOnlyAfterMinCalls() {
        CircuitBreaker b = breaker(10, 4, 50, 1000, 1);
        long t = 100_000;
        for (int i = 0; i < 3; i++) {
            b.onResult(b.tryAcquire(t), true, false, t);
        }
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        b.onResult(b.tryAcquire(t), false, false, t);
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(b.tryAcquire(t + 999)).isEqualTo(CircuitBreaker.REJECTED);
    }

    @Test
    void oldBucketsExpireFromTheWindow() {
        CircuitBreaker b = breaker(5, 4, 50, 1000, 1);
        long t = 100_000;
        b.onResult(b.tryAcquire(t), true, false, t);
        b.onResult(b.tryAcquire(t), true, false, t);
        // Six seconds later the two failures are out of the 5 s window: 2 failures of 4 needed again.
        long later = t + 6_000;
        b.onResult(b.tryAcquire(later), false, false, later);
        b.onResult(b.tryAcquire(later), false, false, later);
        b.onResult(b.tryAcquire(later), true, false, later);
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        b.onResult(b.tryAcquire(later), true, false, later);
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void halfOpenLimitsTrialsAndAnyFailureReopens() {
        CircuitBreaker b = breaker(10, 1, 100, 1000, 2);
        long t = 100_000;
        b.onResult(b.tryAcquire(t), true, false, t);
        long after = t + 1000;
        int p1 = b.tryAcquire(after);
        int p2 = b.tryAcquire(after);
        assertThat(p1).isEqualTo(CircuitBreaker.TRIAL_PERMIT);
        assertThat(p2).isEqualTo(CircuitBreaker.TRIAL_PERMIT);
        assertThat(b.tryAcquire(after)).isEqualTo(CircuitBreaker.REJECTED);
        b.onResult(p1, false, false, after);
        b.onResult(p2, true, false, after);
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);
        // Next window: two clean trials close it.
        long again = after + 1000;
        int q1 = b.tryAcquire(again);
        int q2 = b.tryAcquire(again);
        b.onResult(q1, false, false, again);
        b.onResult(q2, false, false, again);
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void lateResultsOfClosedPermitsDoNotCountAsTrials() {
        CircuitBreaker b = breaker(10, 1, 100, 1000, 1);
        long t = 100_000;
        int early = b.tryAcquire(t);
        b.onResult(b.tryAcquire(t), true, false, t);
        int trial = b.tryAcquire(t + 1000);
        b.onResult(early, false, false, t + 1000);
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        // A released trial frees its slot.
        b.release(trial);
        assertThat(b.tryAcquire(t + 1000)).isEqualTo(CircuitBreaker.TRIAL_PERMIT);
    }

    @Test
    void retryBudgetAllowsAShareOfRequestsPlusAFloor() {
        RetryBudget budget = new RetryBudget();
        long t = 50_000;
        for (int i = 0; i < 100; i++) {
            budget.onRequest(t);
        }
        int granted = 0;
        for (int i = 0; i < 100; i++) {
            granted += budget.tryRetry(t, 20, 1) ? 1 : 0;
        }
        assertThat(granted).isEqualTo(20 + 10);
        // Eleven seconds later the window is empty again: only the floor applies.
        int later = 0;
        for (int i = 0; i < 100; i++) {
            later += budget.tryRetry(t + 11_000, 20, 1) ? 1 : 0;
        }
        assertThat(later).isEqualTo(10);
    }
}
