package apigw.core.observe;

/**
 * Meters of one route, resolved when the snapshot compiles so the request path does no lookups.
 * Implementations are thread-safe and allocation-free per call.
 */
public interface RouteTelemetry {

    /** Records nothing: routes compiled without telemetry (tests, control-plane validation). */
    RouteTelemetry NONE = new RouteTelemetry() {
        @Override
        public void completed(int status, boolean local, long durationNanos) {
            // Intentionally empty.
        }

        @Override
        public void attempt(AttemptOutcome outcome, int status, long durationNanos, int attempt) {
            // Intentionally empty.
        }
    };

    /**
     * The exchange finished.
     *
     * @param status final status (499 when the client went away first)
     * @param local  true when the gateway produced the response itself (auth failure, rate limit,
     *               cache hit, proxy error), false when it came from the upstream
     */
    void completed(int status, boolean local, long durationNanos);

    /**
     * One upstream attempt ended.
     *
     * @param status        upstream status for {@link AttemptOutcome#RESPONSE}, else 0
     * @param durationNanos attempt start to response head (or failure); 0 when nothing was sent
     * @param attempt       0 for the first attempt, 1.. for retries
     */
    void attempt(AttemptOutcome outcome, int status, long durationNanos, int attempt);
}
