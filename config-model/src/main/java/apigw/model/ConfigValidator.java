package apigw.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cross-object rules for a decoded configuration: identifiers, references, path and host syntax,
 * limits. The gateway and the control plane run the same validator. Plugin configs are validated by
 * each plugin's factory when the snapshot is compiled.
 */
public final class ConfigValidator {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern HOST_LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]{0,63})}");
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS",
            "TRACE", "CONNECT");
    private static final int MAX_TIMEOUT_MS = 24 * 60 * 60 * 1000;

    private final List<ConfigError> errors = new ArrayList<>();

    private ConfigValidator() {
    }

    /** Throws with every problem found; returns normally when the config is valid. */
    public static void validate(GatewayConfig c) throws ConfigException {
        ConfigValidator v = new ConfigValidator();
        v.check(c);
        if (!v.errors.isEmpty()) {
            throw new ConfigException(v.errors);
        }
    }

    private void err(String path, String message) {
        errors.add(new ConfigError(path, message));
    }

    private void check(GatewayConfig c) {
        if (c.schemaVersion() < 1 || c.schemaVersion() > GatewayConfig.SUPPORTED_SCHEMA_VERSION) {
            err("schemaVersion", "unsupported schema version " + c.schemaVersion() + " (this build supports 1.."
                    + GatewayConfig.SUPPORTED_SCHEMA_VERSION + ")");
        }
        settings(c.settings());
        Set<String> upstreamIds = ids(c.upstreams().stream().map(Upstream::id).toList(), "upstreams");
        Set<String> serviceIds = ids(c.services().stream().map(Service::id).toList(), "services");
        ids(c.routes().stream().map(Route::id).toList(), "routes");
        for (int i = 0; i < c.upstreams().size(); i++) {
            upstream(c.upstreams().get(i), "upstreams[" + i + "]");
        }
        for (int i = 0; i < c.services().size(); i++) {
            Service s = c.services().get(i);
            String p = "services[" + i + "]";
            if (!upstreamIds.contains(s.upstreamId())) {
                err(p + ".upstreamId", "references unknown upstream '" + s.upstreamId() + "'");
            }
            timeouts(s.timeouts(), p + ".timeouts");
            plugins(s.plugins(), p + ".plugins");
        }
        Map<String, Integer> signatures = new HashMap<>();
        for (int i = 0; i < c.routes().size(); i++) {
            Route r = c.routes().get(i);
            String p = "routes[" + i + "]";
            route(r, p, serviceIds);
            String sig = signature(r);
            Integer prev = signatures.putIfAbsent(sig, i);
            if (prev != null) {
                err(p, "matches exactly the same requests as routes[" + prev + "] with the same priority");
            }
        }
        plugins(c.globalPlugins(), "globalPlugins");
    }

    private void settings(Settings s) {
        for (int i = 0; i < s.trustedProxies().size(); i++) {
            try {
                Cidr.parse(s.trustedProxies().get(i));
            } catch (IllegalArgumentException e) {
                err("settings.trustedProxies[" + i + "]", e.getMessage());
            }
        }
        for (int i = 0; i < s.stripRequestHeaders().size(); i++) {
            if (!TOKEN.matcher(s.stripRequestHeaders().get(i)).matches()) {
                err("settings.stripRequestHeaders[" + i + "]", "not a valid header name");
            }
        }
        if (!s.viaName().isEmpty() && !TOKEN.matcher(s.viaName()).matches()) {
            err("settings.viaName", "must be a token (letters, digits, -._~ etc.)");
        }
        if (s.maxRequestBodyBytes() <= 0) {
            err("settings.maxRequestBodyBytes", "must be positive");
        }
        timeouts(s.defaultTimeouts(), "settings.defaultTimeouts");
    }

    private Set<String> ids(List<String> ids, String kind) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            String p = kind + "[" + i + "].id";
            if (id == null || !ID.matcher(id).matches()) {
                err(p, "must match " + ID.pattern());
            } else if (!seen.add(id)) {
                err(p, "duplicate id '" + id + "'");
            }
        }
        return seen;
    }

    private void route(Route r, String p, Set<String> serviceIds) {
        if (!serviceIds.contains(r.serviceId())) {
            err(p + ".serviceId", "references unknown service '" + r.serviceId() + "'");
        }
        path(r.path(), r.match(), p + ".path");
        for (int i = 0; i < r.hosts().size(); i++) {
            host(r.hosts().get(i), p + ".hosts[" + i + "]");
        }
        for (int i = 0; i < r.methods().size(); i++) {
            if (!METHODS.contains(r.methods().get(i))) {
                err(p + ".methods[" + i + "]", "unknown method '" + r.methods().get(i) + "' (use upper case)");
            }
        }
        r.headers().keySet().forEach(h -> {
            if (!TOKEN.matcher(h).matches()) {
                err(p + ".headers." + h, "not a valid header name");
            }
        });
        r.query().keySet().forEach(q -> {
            if (q.isEmpty()) {
                err(p + ".query", "parameter names must not be empty");
            }
        });
        timeouts(r.timeouts(), p + ".timeouts");
        plugins(r.plugins(), p + ".plugins");
    }

    private void path(String path, PathMatch match, String p) {
        if (path == null) {
            return;
        }
        if (!path.startsWith("/")) {
            err(p, "must start with '/'");
            return;
        }
        if (path.contains("//") || path.contains("?") || path.contains("#") || path.contains("%")) {
            err(p, "must not contain '//', '?', '#' or percent-encoding");
        }
        for (String seg : path.substring(1).split("/", -1)) {
            if (".".equals(seg) || "..".equals(seg)) {
                err(p, "must not contain '.' or '..' segments");
            }
        }
        boolean braces = path.indexOf('{') >= 0 || path.indexOf('}') >= 0;
        if (match != PathMatch.TEMPLATE) {
            if (braces) {
                err(p, "'{param}' segments need match: template");
            }
            return;
        }
        Set<String> names = new HashSet<>();
        for (String seg : path.substring(1).split("/", -1)) {
            if (seg.indexOf('{') >= 0 || seg.indexOf('}') >= 0) {
                var m = PARAM.matcher(seg);
                if (!m.matches()) {
                    err(p, "template segment '" + seg + "' must be exactly {name}");
                } else if (!names.add(m.group(1))) {
                    err(p, "duplicate template parameter '" + m.group(1) + "'");
                }
            }
        }
    }

    private void host(String host, String p) {
        String h = host.startsWith("*.") ? host.substring(2) : host;
        if (!host.equals(host.toLowerCase(Locale.ROOT))) {
            err(p, "must be lower case");
            return;
        }
        if (h.isEmpty() || h.length() > 253) {
            err(p, "invalid host");
            return;
        }
        for (String label : h.split("\\.", -1)) {
            if (!HOST_LABEL.matcher(label).matches()) {
                err(p, "invalid host '" + host + "' (wildcards are only allowed as a leading '*.')");
                return;
            }
        }
    }

    private void upstream(Upstream u, String p) {
        if (u.targets().isEmpty()) {
            err(p + ".targets", "at least one target is required");
        }
        for (int i = 0; i < u.targets().size(); i++) {
            Upstream.Target t = u.targets().get(i);
            String tp = p + ".targets[" + i + "]";
            if (t.host() == null || t.host().isBlank() || t.host().contains("/") || t.host().contains(" ")) {
                err(tp + ".host", "must be a host name or IP address");
            }
            if (t.port() < 1 || t.port() > 65_535) {
                err(tp + ".port", "must be between 1 and 65535");
            }
            if (t.weight() < 1 || t.weight() > 1000) {
                err(tp + ".weight", "must be between 1 and 1000");
            }
        }
        if (u.balancer() == Upstream.Balancer.CONSISTENT_HASH) {
            String k = u.hashKey();
            if (!"ip".equals(k) && !(k.startsWith("header:") && TOKEN.matcher(k.substring(7)).matches())) {
                err(p + ".hashKey", "consistent-hash needs hashKey 'ip' or 'header:<name>'");
            }
        } else if (!u.hashKey().isEmpty()) {
            err(p + ".hashKey", "only used with balancer consistent-hash");
        }
        if (u.caFile().isPresent() && u.scheme() != Upstream.Scheme.HTTPS) {
            err(p + ".caFile", "only used with scheme https");
        }
        Upstream.Pool pool = u.pool();
        if (pool.maxConnections() < 1 || pool.maxConnections() > 10_000) {
            err(p + ".pool.maxConnections", "must be between 1 and 10000");
        }
        if (pool.maxPending() < 0 || pool.maxPending() > 100_000) {
            err(p + ".pool.maxPending", "must be between 0 and 100000");
        }
        if (pool.keepAliveSeconds() < 1) {
            err(p + ".pool.keepAliveSeconds", "must be positive");
        }
    }

    private void timeouts(Timeouts t, String p) {
        check(t.connectMs(), p + ".connectMs", 1);
        check(t.firstByteMs(), p + ".firstByteMs", 1);
        check(t.idleMs(), p + ".idleMs", 1);
        check(t.totalMs(), p + ".totalMs", 0);
    }

    private void check(Integer v, String p, int min) {
        if (v != null && (v < min || v > MAX_TIMEOUT_MS)) {
            err(p, "must be between " + min + " and " + MAX_TIMEOUT_MS + " ms");
        }
    }

    private void plugins(List<PluginRef> plugins, String p) {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < plugins.size(); i++) {
            String name = plugins.get(i).name();
            if (name == null || name.isBlank()) {
                err(p + "[" + i + "].name", "is required");
            } else if (!names.add(name)) {
                err(p + "[" + i + "].name", "plugin '" + name + "' is configured twice at this scope");
            }
        }
    }

    private static String signature(Route r) {
        return String.join(",", r.hosts().stream().sorted().toList()) + "|"
                + String.join(",", r.methods().stream().sorted().toList()) + "|" + r.match() + "|"
                + (r.path() == null ? "" : r.path().replaceAll("\\{[^}]*}", "{}")) + "|"
                + new java.util.TreeMap<>(r.headers()) + "|" + new java.util.TreeMap<>(r.query()) + "|" + r.priority();
    }
}
