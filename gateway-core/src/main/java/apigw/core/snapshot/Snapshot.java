package apigw.core.snapshot;

import apigw.core.routing.Router;
import java.util.Map;

/**
 * Immutable compiled configuration. A request reads {@link SnapshotHolder#current()} once and uses
 * that snapshot until it completes, so swaps are invisible to in-flight requests.
 *
 * @param generation strictly increasing per process (compile order); lets per-loop state tell newer from older
 */
public record Snapshot(long generation, long revision, CompiledSettings settings, Router router,
        Map<String, CompiledUpstream> upstreams, int routeCount) {

    public Snapshot {
        upstreams = Map.copyOf(upstreams);
    }
}
