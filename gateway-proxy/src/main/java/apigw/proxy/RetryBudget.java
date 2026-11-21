package apigw.proxy;

/**
 * Per-route retry budget on one event loop: over the last 10 seconds, retries may add at most
 * {@code percent} of the route's requests, plus {@code minPerSecond * 10} always (so a quiet route can
 * still retry). Stops retry storms from multiplying load on a struggling upstream.
 */
final class RetryBudget {

    private static final int SECONDS = 10;

    private final int[] requests = new int[SECONDS];
    private final int[] retries = new int[SECONDS];
    private long lastSecond = Long.MIN_VALUE;
    private int totalRequests;
    private int totalRetries;

    void onRequest(long nowMs) {
        int b = advance(nowMs / 1000);
        requests[b]++;
        totalRequests++;
    }

    boolean tryRetry(long nowMs, int percent, int minPerSecond) {
        int b = advance(nowMs / 1000);
        long allowed = (long) minPerSecond * SECONDS + (long) totalRequests * percent / 100;
        if (totalRetries >= allowed) {
            return false;
        }
        retries[b]++;
        totalRetries++;
        return true;
    }

    private int advance(long second) {
        if (lastSecond == Long.MIN_VALUE || second - lastSecond >= SECONDS) {
            java.util.Arrays.fill(requests, 0);
            java.util.Arrays.fill(retries, 0);
            totalRequests = 0;
            totalRetries = 0;
        } else {
            for (long s = lastSecond + 1; s <= second; s++) {
                int b = Math.floorMod(s, SECONDS);
                totalRequests -= requests[b];
                totalRetries -= retries[b];
                requests[b] = 0;
                retries[b] = 0;
            }
        }
        lastSecond = Math.max(lastSecond, second);
        return Math.floorMod(second, SECONDS);
    }
}
