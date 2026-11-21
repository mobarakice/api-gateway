package apigw.core.snapshot;

import apigw.core.auth.SecurityIndex;
import apigw.core.routing.Router;
import java.util.Map;
import java.util.Set;

/**
 * Immutable compiled configuration. A request reads {@link SnapshotHolder#current()} once and uses
 * that snapshot until it completes, so swaps are invisible to in-flight requests.
 *
 * @param generation strictly increasing per process (compile order); lets per-loop state tell newer from older
 * @param security   consumers, API keys and revocations for authentication plugins
 * @param resources  long-lived objects plugin instances depend on (see {@code PluginContext#use})
 * @param cachePurges route and tag invalidations for the response cache
 */
public record Snapshot(long generation, long revision, CompiledSettings settings, Router router,
        Map<String, CompiledUpstream> upstreams, int routeCount, SecurityIndex security, Set<Object> resources,
        CachePurges cachePurges) {

    public Snapshot {
        upstreams = Map.copyOf(upstreams);
        resources = Set.copyOf(resources);
    }
}
