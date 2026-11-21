package apigw.core.snapshot;

import apigw.core.routing.MethodBits;
import apigw.model.Retries;

/**
 * A route's retry policy, resolved at compile time (route replaces service). {@link #NONE} when
 * retries are off, so the proxy's retry checks reduce to one int compare.
 */
public record RetryPolicy(int attempts, boolean onConnectFailure, boolean onReset, boolean onTimeout,
        boolean on502, boolean on503, boolean on504, int backoffMs, int maxBackoffMs, int budgetPercent,
        int minRetriesPerSecond, int methodBits) {

    public static final RetryPolicy NONE = new RetryPolicy(0, false, false, false, false, false, false, 0, 0, 0, 0,
            0);

    static RetryPolicy of(Retries r) {
        if (r == null || r.attempts() == 0) {
            return NONE;
        }
        return new RetryPolicy(r.attempts(), r.on().contains(Retries.RetryOn.CONNECT_FAILURE),
                r.on().contains(Retries.RetryOn.RESET), r.on().contains(Retries.RetryOn.TIMEOUT),
                r.on().contains(Retries.RetryOn.STATUS_502), r.on().contains(Retries.RetryOn.STATUS_503),
                r.on().contains(Retries.RetryOn.STATUS_504), r.backoffMs(), r.maxBackoffMs(), r.budgetPercent(),
                r.minRetriesPerSecond(), MethodBits.of(r.methods()));
    }

    /** True if {@code method} may be retried. */
    public boolean allows(String method) {
        return attempts > 0 && (methodBits & MethodBits.of(method)) != 0;
    }

    public boolean onStatus(int status) {
        return status == 502 && on502 || status == 503 && on503 || status == 504 && on504;
    }
}
