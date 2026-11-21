package apigw.model;

import java.util.List;
import java.util.Set;

/**
 * Retry policy for a service or route (the route's replaces the service's as a whole). Retries only
 * happen before any response byte reached the client, only for {@link #methods()}, and a failure after
 * the request body started streaming is retried only if the request had no body (ADR 0017).
 *
 * @param attempts            extra attempts after the first (0 = off)
 * @param on                  which failures are retried
 * @param backoffMs           base backoff; attempt n waits a random time in [0, min(max, base * 2^(n-1))]
 * @param maxBackoffMs        backoff cap
 * @param budgetPercent       retries may add at most this share of the route's requests (per 10 s window)
 * @param minRetriesPerSecond retries always allowed regardless of the budget (low-traffic routes)
 * @param methods             methods that may be retried; default the idempotent ones
 */
public record Retries(int attempts, Set<RetryOn> on, int backoffMs, int maxBackoffMs, int budgetPercent,
        int minRetriesPerSecond, List<String> methods) {

    /** A retryable failure. */
    public enum RetryOn { CONNECT_FAILURE, RESET, TIMEOUT, STATUS_502, STATUS_503, STATUS_504 }

    public static final Set<RetryOn> DEFAULT_ON = Set.of(RetryOn.CONNECT_FAILURE, RetryOn.RESET,
            RetryOn.STATUS_502, RetryOn.STATUS_503, RetryOn.STATUS_504);
    public static final List<String> IDEMPOTENT = List.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE");
    public static final Retries NONE = new Retries(0, Set.of(), 25, 250, 20, 3, IDEMPOTENT);

    public Retries {
        on = Set.copyOf(on);
        methods = List.copyOf(methods);
    }
}
