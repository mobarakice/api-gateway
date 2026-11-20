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
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    /** Revocation entries are compiled into every snapshot; keep the set bounded (ADR 0015). */
    public static final int MAX_REVOKED_TOKENS = 100_000;

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
        Set<String> routeIds = ids(c.routes().stream().map(Route::id).toList(), "routes");
        Map<String, String> upstreamTenant = new HashMap<>();
        c.upstreams().forEach(u -> upstreamTenant.put(u.id(), u.tenantId()));
        Map<String, String> serviceTenant = new HashMap<>();
        c.services().forEach(sv -> serviceTenant.put(sv.id(), sv.tenantId()));
        List<Cidr> allow = new ArrayList<>();
        for (String cidr : c.settings().upstreamAllowCidrs()) {
            try {
                allow.add(Cidr.parse(cidr));
            } catch (IllegalArgumentException e) {
                // reported by settings()
            }
        }
        for (int i = 0; i < c.upstreams().size(); i++) {
            upstream(c.upstreams().get(i), "upstreams[" + i + "]", allow);
            tenantId(c.upstreams().get(i).tenantId(), "upstreams[" + i + "].tenantId");
        }
        for (int i = 0; i < c.services().size(); i++) {
            Service s = c.services().get(i);
            String p = "services[" + i + "]";
            if (!upstreamIds.contains(s.upstreamId())) {
                err(p + ".upstreamId", "references unknown upstream '" + s.upstreamId() + "'");
            }
            timeouts(s.timeouts(), p + ".timeouts");
            plugins(s.plugins(), p + ".plugins");
            tenantId(s.tenantId(), p + ".tenantId");
            crossTenant(s.tenantId(), upstreamTenant.get(s.upstreamId()), p + ".upstreamId", "upstream");
        }
        Map<String, Integer> signatures = new HashMap<>();
        for (int i = 0; i < c.routes().size(); i++) {
            Route r = c.routes().get(i);
            String p = "routes[" + i + "]";
            route(r, p, serviceIds);
            tenantId(r.tenantId(), p + ".tenantId");
            crossTenant(r.tenantId(), serviceTenant.get(r.serviceId()), p + ".serviceId", "service");
            String sig = signature(r);
            Integer prev = signatures.putIfAbsent(sig, i);
            if (prev != null) {
                err(p, "matches exactly the same requests as routes[" + prev + "] with the same priority");
            }
        }
        plugins(c.globalPlugins(), "globalPlugins");
        security(c, routeIds);
    }

    /**
     * A tenant-scoped object may use a shared (tenant-less) dependency or one of its own tenant, never
     * another tenant's; a shared object may not depend on a tenant's.
     */
    private void crossTenant(String owner, String dependency, String p, String what) {
        if (dependency != null && !dependency.equals(owner)) {
            err(p, "references a " + what + " of another tenant");
        }
    }

    private void tenantId(String tenant, String p) {
        if (tenant != null && !ID.matcher(tenant).matches()) {
            err(p, "must match " + ID.pattern());
        }
    }

    private void security(GatewayConfig c, Set<String> routeIds) {
        Set<String> consumerIds = ids(c.consumers().stream().map(Consumer::id).toList(), "consumers");
        Map<String, String> consumerTenant = new HashMap<>();
        for (int i = 0; i < c.consumers().size(); i++) {
            Consumer k = c.consumers().get(i);
            String p = "consumers[" + i + "]";
            consumerTenant.put(k.id(), k.tenantId());
            tenantId(k.tenantId(), p + ".tenantId");
            for (int j = 0; j < k.roles().size(); j++) {
                if (!ID.matcher(k.roles().get(j)).matches()) {
                    err(p + ".roles[" + j + "]", "must match " + ID.pattern());
                }
            }
            plugins(k.plugins(), p + ".plugins");
        }
        Set<String> credentialKeys = new HashSet<>();
        Set<String> keyIds = new HashSet<>();
        for (int i = 0; i < c.credentials().size(); i++) {
            Credential k = c.credentials().get(i);
            String p = "credentials[" + i + "]";
            if (k.id() == null || !ID.matcher(k.id()).matches()) {
                err(p + ".id", "must match " + ID.pattern());
            } else if (!credentialKeys.add(k.consumerId() + "/" + k.id())) {
                err(p + ".id", "duplicate credential '" + k.id() + "' for consumer '" + k.consumerId() + "'");
            }
            if (!consumerIds.contains(k.consumerId())) {
                err(p + ".consumerId", "references unknown consumer '" + k.consumerId() + "'");
            } else if (k.tenantId() != null && !k.tenantId().equals(consumerTenant.get(k.consumerId()))) {
                err(p + ".tenantId", "must equal the consumer's tenant");
            }
            if (k.type() == Credential.Type.API_KEY) {
                if (k.keyId() == null || !KEY_ID.matcher(k.keyId()).matches()) {
                    err(p + ".keyId", "must match " + KEY_ID.pattern());
                } else if (!keyIds.add(k.keyId())) {
                    err(p + ".keyId", "duplicate keyId (key IDs are global)");
                }
                try {
                    ApiKeyHash.parse(k.hash());
                } catch (IllegalArgumentException e) {
                    err(p + ".hash", e.getMessage());
                }
            }
        }
        Set<String> pluginConfigIds = ids(c.pluginConfigs().stream().map(PluginConfigSet::id).toList(),
                "pluginConfigs");
        Map<String, String> pluginConfigName = new HashMap<>();
        c.pluginConfigs().forEach(pc -> pluginConfigName.put(pc.id(), pc.plugin()));
        for (int i = 0; i < c.pluginConfigs().size(); i++) {
            if (c.pluginConfigs().get(i).plugin() == null || c.pluginConfigs().get(i).plugin().isBlank()) {
                err("pluginConfigs[" + i + "].plugin", "is required");
            }
        }
        configRefs(c.globalPlugins(), "globalPlugins", pluginConfigIds, pluginConfigName);
        for (int i = 0; i < c.services().size(); i++) {
            configRefs(c.services().get(i).plugins(), "services[" + i + "].plugins", pluginConfigIds, pluginConfigName);
        }
        for (int i = 0; i < c.routes().size(); i++) {
            configRefs(c.routes().get(i).plugins(), "routes[" + i + "].plugins", pluginConfigIds, pluginConfigName);
        }
        for (int i = 0; i < c.consumers().size(); i++) {
            configRefs(c.consumers().get(i).plugins(), "consumers[" + i + "].plugins", pluginConfigIds,
                    pluginConfigName);
        }
        ids(c.authzPolicies().stream().map(AuthzPolicy::id).toList(), "authzPolicies");
        for (int i = 0; i < c.authzPolicies().size(); i++) {
            AuthzPolicy a = c.authzPolicies().get(i);
            String p = "authzPolicies[" + i + "]";
            tenantId(a.tenantId(), p + ".tenantId");
            for (int j = 0; j < a.routes().size(); j++) {
                if (!routeIds.contains(a.routes().get(j))) {
                    err(p + ".routes[" + j + "]", "references unknown route '" + a.routes().get(j) + "'");
                }
            }
            for (int j = 0; j < a.methods().size(); j++) {
                if (!METHODS.contains(a.methods().get(j))) {
                    err(p + ".methods[" + j + "]", "unknown method '" + a.methods().get(j) + "'");
                }
            }
            for (int j = 0; j < a.subjects().consumers().size(); j++) {
                if (!consumerIds.contains(a.subjects().consumers().get(j))) {
                    err(p + ".subjects.consumers[" + j + "]", "references unknown consumer '"
                            + a.subjects().consumers().get(j) + "'");
                }
            }
        }
        if (c.revokedTokens().size() > MAX_REVOKED_TOKENS) {
            err("revokedTokens", "more than " + MAX_REVOKED_TOKENS + " entries; revoke by rotating the issuer key "
                    + "or shortening token lifetimes instead");
        }
        for (int i = 0; i < c.revokedTokens().size(); i++) {
            RevokedToken t = c.revokedTokens().get(i);
            if (t.jti() == null || t.jti().isEmpty() || t.jti().length() > 512) {
                err("revokedTokens[" + i + "].jti", "must be 1..512 characters");
            }
        }
    }

    private void configRefs(List<PluginRef> refs, String p, Set<String> ids, Map<String, String> names) {
        for (int i = 0; i < refs.size(); i++) {
            String ref = refs.get(i).configRef();
            if (ref == null) {
                continue;
            }
            if (!ids.contains(ref)) {
                err(p + "[" + i + "].configRef", "references unknown plugin config '" + ref + "'");
            } else if (!ref.isEmpty() && !java.util.Objects.equals(names.get(ref), refs.get(i).name())) {
                err(p + "[" + i + "].configRef", "plugin config '" + ref + "' is for plugin '" + names.get(ref)
                        + "', not '" + refs.get(i).name() + "'");
            }
        }
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
        for (int i = 0; i < s.upstreamAllowCidrs().size(); i++) {
            try {
                Cidr.parse(s.upstreamAllowCidrs().get(i));
            } catch (IllegalArgumentException e) {
                err("settings.upstreamAllowCidrs[" + i + "]", e.getMessage());
            }
        }
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

    private void upstream(Upstream u, String p, List<Cidr> allow) {
        if (u.targets().isEmpty()) {
            err(p + ".targets", "at least one target is required");
        }
        for (int i = 0; i < u.targets().size(); i++) {
            Upstream.Target t = u.targets().get(i);
            String tp = p + ".targets[" + i + "]";
            if (t.host() == null || t.host().isBlank() || t.host().contains("/") || t.host().contains(" ")) {
                err(tp + ".host", "must be a host name or IP address");
            }
            literalTarget(t.host(), tp + ".host", allow);
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

    /** SSRF check for IP-literal targets; names are checked at connect time (DNS rebinding). */
    private void literalTarget(String host, String p, List<Cidr> allow) {
        if (host == null) {
            return;
        }
        try {
            byte[] literal = Cidr.literal(host);
            if (!AddressPolicy.permitted(literal, allow)) {
                err(p, "address in a denied range (loopback, private, link-local, metadata, ...); allow it "
                        + "explicitly with settings.upstreamAllowCidrs");
            }
        } catch (IllegalArgumentException e) {
            // not an IP literal: a host name
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
