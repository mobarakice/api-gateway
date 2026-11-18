package apigw.core.routing;

import apigw.core.snapshot.CompiledRoute;

/**
 * Caller-owned, reusable result holder for one lookup (one per event loop). Records template
 * parameter positions as offsets into the request path, so matching allocates nothing.
 */
public final class RouteMatch {

    static final int MAX_PARAMS = 32;

    final int[] starts = new int[MAX_PARAMS];
    final int[] ends = new int[MAX_PARAMS];
    int count;
    private String path;
    private CompiledRoute route;

    void reset(String requestPath) {
        this.path = requestPath;
        this.count = 0;
        this.route = null;
    }

    void found(CompiledRoute r) {
        this.route = r;
    }

    public CompiledRoute route() {
        return route;
    }

    /** Copies parameter offsets out, for use after this holder is reused. */
    public int[] paramOffsets() {
        int[] out = new int[count * 2];
        for (int i = 0; i < count; i++) {
            out[2 * i] = starts[i];
            out[2 * i + 1] = ends[i];
        }
        return out;
    }

    /** Value of the {@code i}-th template parameter (allocates the substring). */
    public String param(int i) {
        return i < count ? path.substring(starts[i], ends[i]) : null;
    }

    public int paramCount() {
        return count;
    }
}
