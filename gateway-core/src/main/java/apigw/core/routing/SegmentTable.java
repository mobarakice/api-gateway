package apigw.core.routing;

import java.util.Map;

/**
 * Immutable open-addressing map from path segment to child node, looked up by a region of the
 * request path without creating a substring. Hash = {@link String#hashCode()} over the region.
 */
final class SegmentTable {

    private static final SegmentTable EMPTY = new SegmentTable(new String[1], new RouteTrie.Node[1], 0);

    private final String[] keys;
    private final RouteTrie.Node[] values;
    private final int mask;

    private SegmentTable(String[] keys, RouteTrie.Node[] values, int mask) {
        this.keys = keys;
        this.values = values;
        this.mask = mask;
    }

    static SegmentTable of(Map<String, RouteTrie.Node> entries) {
        if (entries.isEmpty()) {
            return EMPTY;
        }
        int cap = Integer.highestOneBit(Math.max(2, entries.size() * 2 - 1)) << 1;
        String[] keys = new String[cap];
        RouteTrie.Node[] values = new RouteTrie.Node[cap];
        int mask = cap - 1;
        for (Map.Entry<String, RouteTrie.Node> e : entries.entrySet()) {
            int i = e.getKey().hashCode() & mask;
            while (keys[i] != null) {
                i = (i + 1) & mask;
            }
            keys[i] = e.getKey();
            values[i] = e.getValue();
        }
        return new SegmentTable(keys, values, mask);
    }

    /** Child for {@code path[start, end)}, or {@code null}. */
    RouteTrie.Node get(String path, int start, int end) {
        int h = 0;
        for (int i = start; i < end; i++) {
            h = 31 * h + path.charAt(i);
        }
        int len = end - start;
        int i = h & mask;
        String k;
        while ((k = keys[i]) != null) {
            if (k.length() == len && k.regionMatches(0, path, start, len)) {
                return values[i];
            }
            i = (i + 1) & mask;
        }
        return null;
    }
}
