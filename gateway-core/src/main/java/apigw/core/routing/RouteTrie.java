package apigw.core.routing;

import apigw.core.snapshot.CompiledRoute;
import apigw.model.PathMatch;
import io.vertx.core.MultiMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Segment radix trie for one host scope. At each segment the lookup tries, in order: the static
 * child, the parameter child, then prefix routes ending at this node (backtracking on failure). So
 * a static segment beats a parameter, a parameter beats a prefix, and a longer prefix beats a
 * shorter one. Among routes ending at the same node, higher priority wins, then more predicates
 * (headers/query), then declaration order.
 */
public final class RouteTrie {

    private final Node root;

    private RouteTrie(Node root) {
        this.root = root;
    }

    /** Immutable trie node. */
    static final class Node {
        final SegmentTable statics;
        final Node param;
        final Candidate[] terminal;
        final Candidate[] prefix;

        Node(SegmentTable statics, Node param, Candidate[] terminal, Candidate[] prefix) {
            this.statics = statics;
            this.param = param;
            this.terminal = terminal;
            this.prefix = prefix;
        }
    }

    /** A route at a node plus the predicates it needs beyond the path. */
    static final class Candidate {
        final CompiledRoute route;
        final int methods;
        final String[] headerNames;
        final String[] headerValues;
        final String[] queryNames;
        final String[] queryValues;
        final int priority;
        final int order;

        Candidate(CompiledRoute route, int methods, Map<String, String> headers, Map<String, String> query,
                int priority, int order) {
            this.route = route;
            this.methods = methods;
            this.headerNames = headers.keySet().stream().map(h -> h.toLowerCase(Locale.ROOT)).toArray(String[]::new);
            this.headerValues = headers.keySet().stream().map(headers::get).toArray(String[]::new);
            this.queryNames = query.keySet().toArray(String[]::new);
            this.queryValues = query.keySet().stream().map(query::get).toArray(String[]::new);
            this.priority = priority;
            this.order = order;
        }

        int predicateCount() {
            return headerNames.length + queryNames.length;
        }

        boolean accepts(int methodBit, MultiMap headers, String rawQuery) {
            if (!MethodBits.allows(methods, methodBit)) {
                return false;
            }
            for (int i = 0; i < headerNames.length; i++) {
                if (!headerValues[i].equals(headers.get(headerNames[i]))) {
                    return false;
                }
            }
            for (int i = 0; i < queryNames.length; i++) {
                if (!queryHas(rawQuery, queryNames[i], queryValues[i])) {
                    return false;
                }
            }
            return true;
        }
    }

    /** True if the raw query has {@code name=value} as one of its {@code &}-separated pairs (compared as sent). */
    static boolean queryHas(String q, String name, String value) {
        if (q == null || q.isEmpty()) {
            return false;
        }
        int n = name.length();
        int v = value.length();
        int pos = 0;
        while (pos <= q.length()) {
            int amp = q.indexOf('&', pos);
            int end = amp < 0 ? q.length() : amp;
            if (end - pos == n + 1 + v && q.regionMatches(pos, name, 0, n) && q.charAt(pos + n) == '='
                    && q.regionMatches(pos + n + 1, value, 0, v)) {
                return true;
            }
            if (amp < 0) {
                return false;
            }
            pos = amp + 1;
        }
        return false;
    }

    /**
     * Finds the route for {@code path} (which must start with '/' and contain no empty segments
     * other than a trailing one). On success sets {@code match.route()} and parameter offsets.
     */
    boolean match(String path, int methodBit, MultiMap headers, String rawQuery, RouteMatch match) {
        int start = path.length() == 1 ? 2 : 1;
        return walk(root, path, start, methodBit, headers, rawQuery, match);
    }

    private static boolean walk(Node node, String path, int pos, int methodBit, MultiMap headers, String rawQuery,
            RouteMatch match) {
        int len = path.length();
        if (pos > len) {
            if (pick(node.terminal, methodBit, headers, rawQuery, match)) {
                return true;
            }
            return pick(node.prefix, methodBit, headers, rawQuery, match);
        }
        int slash = path.indexOf('/', pos);
        int end = slash < 0 ? len : slash;
        Node child = node.statics.get(path, pos, end);
        if (child != null && walk(child, path, end + 1, methodBit, headers, rawQuery, match)) {
            return true;
        }
        if (node.param != null && end > pos && match.count < RouteMatch.MAX_PARAMS) {
            int slot = match.count++;
            match.starts[slot] = pos;
            match.ends[slot] = end;
            if (walk(node.param, path, end + 1, methodBit, headers, rawQuery, match)) {
                return true;
            }
            match.count = slot;
        }
        return pick(node.prefix, methodBit, headers, rawQuery, match);
    }

    private static boolean pick(Candidate[] candidates, int methodBit, MultiMap headers, String rawQuery,
            RouteMatch match) {
        for (Candidate c : candidates) {
            if (c.accepts(methodBit, headers, rawQuery)) {
                match.found(c.route);
                return true;
            }
        }
        return false;
    }

    /** Segments of a route path: "/" has none; "/a/" has "a" and "". */
    static List<String> segments(String path) {
        if ("/".equals(path)) {
            return List.of();
        }
        return List.of(path.substring(1).split("/", -1));
    }

    /** Mutable builder; {@link #build()} freezes it. Not thread-safe. */
    public static final class Builder {

        private final BuilderNode root = new BuilderNode();
        private int order;

        /** One route as the trie needs it. */
        public record Entry(CompiledRoute route, String path, PathMatch match, int methods,
                Map<String, String> headers, Map<String, String> query, int priority) {
        }

        public Builder add(Entry e) {
            String path = e.path();
            if (e.match() == PathMatch.PREFIX && path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            BuilderNode n = root;
            for (String seg : segments(path)) {
                if (e.match() == PathMatch.TEMPLATE && seg.startsWith("{")) {
                    if (n.param == null) {
                        n.param = new BuilderNode();
                    }
                    n = n.param;
                } else {
                    n = n.statics.computeIfAbsent(seg, k -> new BuilderNode());
                }
            }
            Candidate c = new Candidate(e.route(), e.methods(), e.headers(), e.query(), e.priority(), order++);
            (e.match() == PathMatch.PREFIX ? n.prefix : n.terminal).add(c);
            return this;
        }

        public RouteTrie build() {
            return new RouteTrie(root.freeze());
        }
    }

    private static final Comparator<Candidate> ORDER = Comparator
            .comparingInt((Candidate c) -> -c.priority)
            .thenComparingInt(c -> -c.predicateCount())
            .thenComparingInt(c -> c.order);

    private static final class BuilderNode {
        final Map<String, BuilderNode> statics = new HashMap<>();
        BuilderNode param;
        final List<Candidate> terminal = new ArrayList<>();
        final List<Candidate> prefix = new ArrayList<>();

        Node freeze() {
            Map<String, Node> frozen = new HashMap<>();
            statics.forEach((k, v) -> frozen.put(k, v.freeze()));
            terminal.sort(ORDER);
            prefix.sort(ORDER);
            return new Node(SegmentTable.of(frozen), param == null ? null : param.freeze(),
                    terminal.toArray(Candidate[]::new), prefix.toArray(Candidate[]::new));
        }
    }
}
