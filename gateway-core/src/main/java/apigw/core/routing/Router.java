package apigw.core.routing;

import io.vertx.core.MultiMap;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

/**
 * Host table in front of {@link RouteTrie}s. Lookup order: exact host, then wildcard hosts from the
 * longest suffix down ({@code *.example.com} matches any name ending in {@code .example.com}), then
 * routes with no host; the first scope with a matching route wins.
 */
public final class Router {

    private final Map<String, RouteTrie> exact;
    private final String[] wildcardSuffixes;
    private final RouteTrie[] wildcardTries;
    private final RouteTrie anyHost;

    /**
     * @param exact     lower-case host → trie
     * @param wildcards {@code ".example.com"} suffix → trie
     * @param anyHost   routes without hosts; may be null
     */
    public Router(Map<String, RouteTrie> exact, Map<String, RouteTrie> wildcards, RouteTrie anyHost) {
        this.exact = Map.copyOf(exact);
        String[] suffixes = wildcards.keySet().toArray(String[]::new);
        Arrays.sort(suffixes, Comparator.comparingInt(String::length).reversed().thenComparing(s -> s));
        this.wildcardSuffixes = suffixes;
        this.wildcardTries = Arrays.stream(suffixes).map(wildcards::get).toArray(RouteTrie[]::new);
        this.anyHost = anyHost;
    }

    /** True if some route could match this host (used for Host validation). */
    public boolean servesHost(String host) {
        if (anyHost != null || exact.containsKey(host)) {
            return true;
        }
        for (String s : wildcardSuffixes) {
            if (host.length() > s.length() && host.endsWith(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves a request. {@code host} must already be normalised ({@link #normalizeHost}).
     *
     * @return true when a route matched; the route and parameters are in {@code match}
     */
    public boolean match(String host, String method, String path, String rawQuery, MultiMap headers,
            RouteMatch match) {
        match.reset(path);
        int bit = MethodBits.of(method);
        RouteTrie t = exact.get(host);
        if (t != null && t.match(path, bit, headers, rawQuery, match)) {
            return true;
        }
        for (int i = 0; i < wildcardSuffixes.length; i++) {
            String s = wildcardSuffixes[i];
            if (host.length() > s.length() && host.endsWith(s)) {
                match.reset(path);
                if (wildcardTries[i].match(path, bit, headers, rawQuery, match)) {
                    return true;
                }
            }
        }
        match.reset(path);
        return anyHost != null && anyHost.match(path, bit, headers, rawQuery, match);
    }

    /**
     * Host without port, lower case. Returns the argument itself when it is already normalised
     * (the common case), so no allocation. Returns {@code null} for a malformed authority.
     */
    public static String normalizeHost(String authority) {
        if (authority == null || authority.isEmpty()) {
            return null;
        }
        int end = authority.length();
        if (authority.charAt(0) == '[') {
            int close = authority.indexOf(']');
            if (close < 0) {
                return null;
            }
            end = close + 1;
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                end = colon;
            }
        }
        boolean lower = true;
        for (int i = 0; i < end; i++) {
            char c = authority.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                lower = false;
            } else if (c <= ' ' || c == '/' || c == '@' || c == '\\' || c >= 0x7f) {
                return null;
            }
        }
        if (end == 0) {
            return null;
        }
        String h = end == authority.length() ? authority : authority.substring(0, end);
        return lower ? h : h.toLowerCase(java.util.Locale.ROOT);
    }
}
