package apigw.core.snapshot;

import apigw.model.CachePurge;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Latest purge time per route and per tag (epoch millis). A cached entry stored at or before the
 * relevant purge time is stale (ADR 0019).
 */
public record CachePurges(Map<String, Long> byRoute, Map<String, Long> byTag) {

    public static final CachePurges NONE = new CachePurges(Map.of(), Map.of());

    public CachePurges {
        byRoute = Map.copyOf(byRoute);
        byTag = Map.copyOf(byTag);
    }

    public static CachePurges of(List<CachePurge> purges) {
        if (purges.isEmpty()) {
            return NONE;
        }
        Map<String, Long> routes = new HashMap<>();
        Map<String, Long> tags = new HashMap<>();
        for (CachePurge p : purges) {
            long at = p.issuedAt().toEpochMilli();
            if (p.routeId() != null) {
                routes.merge(p.routeId(), at, Math::max);
            } else {
                tags.merge(p.tag(), at, Math::max);
            }
        }
        return new CachePurges(routes, tags);
    }

    /** True if an entry of {@code routeId} with {@code tags}, stored at {@code storedAtMs}, was purged since. */
    public boolean purged(String routeId, String[] tags, long storedAtMs) {
        if (byRoute.isEmpty() && byTag.isEmpty()) {
            return false;
        }
        Long r = byRoute.get(routeId);
        if (r != null && storedAtMs <= r) {
            return true;
        }
        if (!byTag.isEmpty()) {
            for (String t : tags) {
                Long at = byTag.get(t);
                if (at != null && storedAtMs <= at) {
                    return true;
                }
            }
        }
        return false;
    }
}
