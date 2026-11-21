package apigw.proxy;

import apigw.model.Upstream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which targets of one upstream may take traffic on this event loop: not marked unhealthy by active
 * probes, and not ejected by passive outlier detection. Passive state is loop-local (no locks); the
 * active verdict is shared ({@link ActiveHealth}).
 */
final class TargetGate {

    private static final Logger LOG = LoggerFactory.getLogger(TargetGate.class);
    private static final int MAX_EJECTION_MULTIPLIER = 10;

    private final String upstreamId;
    private final ActiveHealth active;
    private final Upstream.Passive passive;
    private final int[] consecutiveFailures;
    private final long[] ejectedUntilMs;
    private final int[] ejections;

    TargetGate(String upstreamId, int targets, ActiveHealth active, Upstream.Passive passive) {
        this.upstreamId = upstreamId;
        this.active = active;
        this.passive = passive;
        this.consecutiveFailures = new int[targets];
        this.ejectedUntilMs = new long[targets];
        this.ejections = new int[targets];
    }

    boolean available(int i, long nowMs) {
        return (active == null || active.healthy(i)) && ejectedUntilMs[i] <= nowMs;
    }

    void onSuccess(int i) {
        consecutiveFailures[i] = 0;
    }

    void onFailure(int i, long nowMs) {
        if (passive == null || ++consecutiveFailures[i] < passive.consecutiveFailures() || ejectedUntilMs[i] > nowMs) {
            return;
        }
        int ejected = 0;
        for (long until : ejectedUntilMs) {
            if (until > nowMs) {
                ejected++;
            }
        }
        if ((ejected + 1) * 100L > (long) passive.maxEjectionPercent() * ejectedUntilMs.length) {
            return;
        }
        int times = Math.min(++ejections[i], MAX_EJECTION_MULTIPLIER);
        ejectedUntilMs[i] = nowMs + (long) passive.ejectionMs() * times;
        consecutiveFailures[i] = 0;
        LOG.warn("upstream target ejected upstream={} target={} consecutiveFailures={} ejectionMs={}", upstreamId, i,
                passive.consecutiveFailures(), (long) passive.ejectionMs() * times);
    }
}
