package apigw.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.NullNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decodes configuration JSON into model records by walking the Jackson tree, without reflection
 * (Native Image needs no metadata) and with every error reported against its field path.
 * Unknown fields are errors: a typo must not silently disable a setting.
 *
 * <p>Structural checks only (types, required fields, enums). Cross-object rules live in
 * {@link ConfigValidator}.
 */
public final class ConfigDecoder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<ConfigError> errors = new ArrayList<>();

    private ConfigDecoder() {
    }

    /** Parses and decodes a whole configuration document. */
    public static GatewayConfig decode(byte[] json) throws ConfigException {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ConfigException(List.of(new ConfigError("$", "not valid JSON: " + e.getOriginalMessage())));
        } catch (IOException e) {
            throw new ConfigException(List.of(new ConfigError("$", "unreadable: " + e.getMessage())));
        }
        return decode(root);
    }

    public static GatewayConfig decode(JsonNode root) throws ConfigException {
        ConfigDecoder d = new ConfigDecoder();
        GatewayConfig config = d.config(root);
        if (!d.errors.isEmpty()) {
            throw new ConfigException(d.errors);
        }
        return config;
    }

    // Single objects, used by the etcd snapshot builder (one key = one object).

    public static Route decodeRoute(JsonNode node, String path) throws ConfigException {
        ConfigDecoder d = new ConfigDecoder();
        Route r = d.route(node, path);
        d.throwIfErrors();
        return r;
    }

    public static Service decodeService(JsonNode node, String path) throws ConfigException {
        ConfigDecoder d = new ConfigDecoder();
        Service s = d.service(node, path);
        d.throwIfErrors();
        return s;
    }

    public static Upstream decodeUpstream(JsonNode node, String path) throws ConfigException {
        ConfigDecoder d = new ConfigDecoder();
        Upstream u = d.upstream(node, path);
        d.throwIfErrors();
        return u;
    }

    public static Settings decodeSettings(JsonNode node, String path) throws ConfigException {
        ConfigDecoder d = new ConfigDecoder();
        Settings s = d.settings(node, path);
        d.throwIfErrors();
        return s;
    }

    /** One plugin reference ({@code name}, {@code enabled}, {@code config}). */
    public static PluginRef decodePluginRef(JsonNode node, String path) throws ConfigException {
        ConfigDecoder d = new ConfigDecoder();
        PluginRef r = d.object(node, path) ? d.pluginRef(node, path) : null;
        d.throwIfErrors();
        return r;
    }

    private void throwIfErrors() throws ConfigException {
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
    }

    private GatewayConfig config(JsonNode n) {
        if (!object(n, "$")) {
            return null;
        }
        known(n, "$", Set.of("schemaVersion", "revision", "settings", "routes", "services", "upstreams",
                "globalPlugins"));
        int schemaVersion = intField(n, "$", "schemaVersion", null);
        long revision = longField(n, "$", "revision", 0L);
        Settings settings = n.has("settings") ? settings(n.get("settings"), "settings") : Settings.DEFAULTS;
        List<Route> routes = new ArrayList<>();
        each(n, "$", "routes", (item, p) -> routes.add(route(item, p)));
        List<Service> services = new ArrayList<>();
        each(n, "$", "services", (item, p) -> services.add(service(item, p)));
        List<Upstream> upstreams = new ArrayList<>();
        each(n, "$", "upstreams", (item, p) -> upstreams.add(upstream(item, p)));
        List<PluginRef> global = plugins(n, "$", "globalPlugins");
        if (!errors.isEmpty()) {
            return null;
        }
        return new GatewayConfig(schemaVersion, revision, settings, routes, services, upstreams, global);
    }

    private Settings settings(JsonNode n, String path) {
        if (!object(n, path)) {
            return Settings.DEFAULTS;
        }
        known(n, path, Set.of("trustedProxies", "stripRequestHeaders", "viaName", "defaultTimeouts",
                "maxRequestBodyBytes"));
        Settings d = Settings.DEFAULTS;
        return new Settings(
                n.has("trustedProxies") ? strings(n, path, "trustedProxies") : d.trustedProxies(),
                n.has("stripRequestHeaders")
                        ? strings(n, path, "stripRequestHeaders").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList()
                        : d.stripRequestHeaders(),
                stringField(n, path, "viaName", d.viaName()),
                n.has("defaultTimeouts") ? timeouts(n.get("defaultTimeouts"), path + ".defaultTimeouts")
                        .orElse(Timeouts.DEFAULTS) : d.defaultTimeouts(),
                longField(n, path, "maxRequestBodyBytes", d.maxRequestBodyBytes()));
    }

    private Route route(JsonNode n, String path) {
        if (!object(n, path)) {
            return null;
        }
        known(n, path, Set.of("id", "hosts", "methods", "path", "match", "headers", "query", "priority",
                "serviceId", "timeouts", "plugins", "preserveHost", "kind", "schemaVersion", "tenantId",
                "updatedAt", "updatedBy"));
        return new Route(
                stringField(n, path, "id", null),
                n.has("hosts") ? strings(n, path, "hosts") : List.of(),
                n.has("methods") ? strings(n, path, "methods") : List.of(),
                stringField(n, path, "path", null),
                enumField(n, path, "match", PathMatch.class, PathMatch.PREFIX),
                stringMap(n, path, "headers"),
                stringMap(n, path, "query"),
                intField(n, path, "priority", 0),
                stringField(n, path, "serviceId", null),
                n.has("timeouts") ? timeouts(n.get("timeouts"), path + ".timeouts") : Timeouts.UNSET,
                plugins(n, path, "plugins"),
                boolField(n, path, "preserveHost", false));
    }

    private Service service(JsonNode n, String path) {
        if (!object(n, path)) {
            return null;
        }
        known(n, path, Set.of("id", "upstreamId", "timeouts", "plugins", "kind", "schemaVersion", "tenantId",
                "updatedAt", "updatedBy"));
        return new Service(
                stringField(n, path, "id", null),
                stringField(n, path, "upstreamId", null),
                n.has("timeouts") ? timeouts(n.get("timeouts"), path + ".timeouts") : Timeouts.UNSET,
                plugins(n, path, "plugins"));
    }

    private Upstream upstream(JsonNode n, String path) {
        if (!object(n, path)) {
            return null;
        }
        known(n, path, Set.of("id", "scheme", "caFile", "targets", "balancer", "hashKey", "pool", "http2",
                "kind", "schemaVersion", "tenantId", "updatedAt", "updatedBy"));
        List<Upstream.Target> targets = new ArrayList<>();
        each(n, path, "targets", (item, p) -> {
            if (object(item, p)) {
                known(item, p, Set.of("host", "port", "weight"));
                targets.add(new Upstream.Target(stringField(item, p, "host", null), intField(item, p, "port", null),
                        intField(item, p, "weight", 1)));
            }
        });
        Upstream.Pool pool = Upstream.Pool.DEFAULTS;
        if (n.has("pool") && object(n.get("pool"), path + ".pool")) {
            JsonNode p = n.get("pool");
            String pp = path + ".pool";
            known(p, pp, Set.of("maxConnections", "maxPending", "keepAliveSeconds"));
            pool = new Upstream.Pool(intField(p, pp, "maxConnections", pool.maxConnections()),
                    intField(p, pp, "maxPending", pool.maxPending()),
                    intField(p, pp, "keepAliveSeconds", pool.keepAliveSeconds()));
        }
        String ca = stringField(n, path, "caFile", "");
        return new Upstream(
                stringField(n, path, "id", null),
                enumField(n, path, "scheme", Upstream.Scheme.class, Upstream.Scheme.HTTP),
                ca.isEmpty() ? Optional.empty() : Optional.of(Path.of(ca)),
                targets,
                enumField(n, path, "balancer", Upstream.Balancer.class, Upstream.Balancer.ROUND_ROBIN),
                stringField(n, path, "hashKey", ""),
                pool,
                boolField(n, path, "http2", false));
    }

    private Timeouts timeouts(JsonNode n, String path) {
        if (!object(n, path)) {
            return Timeouts.UNSET;
        }
        known(n, path, Set.of("connectMs", "firstByteMs", "idleMs", "totalMs"));
        return new Timeouts(optInt(n, path, "connectMs"), optInt(n, path, "firstByteMs"),
                optInt(n, path, "idleMs"), optInt(n, path, "totalMs"));
    }

    private List<PluginRef> plugins(JsonNode parent, String parentPath, String field) {
        List<PluginRef> out = new ArrayList<>();
        each(parent, parentPath, field, (item, p) -> {
            if (object(item, p)) {
                out.add(pluginRef(item, p));
            }
        });
        return out;
    }

    private PluginRef pluginRef(JsonNode item, String p) {
        known(item, p, Set.of("name", "enabled", "config"));
        JsonNode config = item.has("config") ? item.get("config") : MAPPER.createObjectNode();
        if (!config.isObject()) {
            errors.add(new ConfigError(p + ".config", "must be an object"));
        }
        return new PluginRef(stringField(item, p, "name", null), boolField(item, p, "enabled", true),
                config.deepCopy());
    }

    // ---- primitive helpers -------------------------------------------------------------------

    @FunctionalInterface
    private interface ItemVisitor {
        void visit(JsonNode item, String path);
    }

    private boolean object(JsonNode n, String path) {
        if (n == null || !n.isObject()) {
            errors.add(new ConfigError(path, "must be an object"));
            return false;
        }
        return true;
    }

    private void known(JsonNode n, String path, Set<String> allowed) {
        for (Map.Entry<String, JsonNode> e : n.properties()) {
            if (!allowed.contains(e.getKey())) {
                errors.add(new ConfigError(path + "." + e.getKey(), "unknown field"));
            }
        }
    }

    private void each(JsonNode parent, String parentPath, String field, ItemVisitor visitor) {
        JsonNode arr = parent.get(field);
        if (arr == null || arr.isNull()) {
            return;
        }
        String path = join(parentPath, field);
        if (!arr.isArray()) {
            errors.add(new ConfigError(path, "must be an array"));
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            visitor.visit(arr.get(i), path + "[" + i + "]");
        }
    }

    private static String join(String parentPath, String field) {
        return "$".equals(parentPath) ? field : parentPath + "." + field;
    }

    private JsonNode value(JsonNode n, String path, String field, boolean required, JsonNodeType... types) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            if (required) {
                errors.add(new ConfigError(join(path, field), "is required"));
            }
            return NullNode.getInstance();
        }
        for (JsonNodeType t : types) {
            if (v.getNodeType() == t) {
                return v;
            }
        }
        errors.add(new ConfigError(join(path, field), "must be a " + types[0].name().toLowerCase(Locale.ROOT)));
        return NullNode.getInstance();
    }

    private String stringField(JsonNode n, String path, String field, String fallback) {
        JsonNode v = value(n, path, field, fallback == null, JsonNodeType.STRING);
        return v.isTextual() ? v.textValue() : fallback;
    }

    private int intField(JsonNode n, String path, String field, Integer fallback) {
        JsonNode v = value(n, path, field, fallback == null, JsonNodeType.NUMBER);
        if (v.isNumber()) {
            if (!v.canConvertToInt() || !v.isIntegralNumber()) {
                errors.add(new ConfigError(join(path, field), "must be an integer"));
                return fallback == null ? 0 : fallback;
            }
            return v.intValue();
        }
        return fallback == null ? 0 : fallback;
    }

    private Integer optInt(JsonNode n, String path, String field) {
        if (!n.has(field) || n.get(field).isNull()) {
            return null;
        }
        return intField(n, path, field, 0);
    }

    private long longField(JsonNode n, String path, String field, Long fallback) {
        JsonNode v = value(n, path, field, fallback == null, JsonNodeType.NUMBER);
        if (v.isNumber()) {
            if (!v.canConvertToLong() || !v.isIntegralNumber()) {
                errors.add(new ConfigError(join(path, field), "must be an integer"));
                return fallback == null ? 0 : fallback;
            }
            return v.longValue();
        }
        return fallback == null ? 0 : fallback;
    }

    private boolean boolField(JsonNode n, String path, String field, boolean fallback) {
        JsonNode v = value(n, path, field, false, JsonNodeType.BOOLEAN);
        return v.isBoolean() ? v.booleanValue() : fallback;
    }

    private <E extends Enum<E>> E enumField(JsonNode n, String path, String field, Class<E> type, E fallback) {
        JsonNode v = value(n, path, field, fallback == null, JsonNodeType.STRING);
        if (!v.isTextual()) {
            return fallback;
        }
        String raw = v.textValue().trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(raw)) {
                return e;
            }
        }
        StringBuilder allowed = new StringBuilder();
        for (E e : type.getEnumConstants()) {
            allowed.append(allowed.isEmpty() ? "" : ", ").append(e.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        errors.add(new ConfigError(join(path, field), "must be one of: " + allowed));
        return fallback;
    }

    private List<String> strings(JsonNode n, String path, String field) {
        List<String> out = new ArrayList<>();
        each(n, path, field, (item, p) -> {
            if (item.isTextual()) {
                out.add(item.textValue());
            } else {
                errors.add(new ConfigError(p, "must be a string"));
            }
        });
        return out;
    }

    private Map<String, String> stringMap(JsonNode n, String path, String field) {
        JsonNode m = n.get(field);
        if (m == null || m.isNull()) {
            return Map.of();
        }
        String p = join(path, field);
        if (!m.isObject()) {
            errors.add(new ConfigError(p, "must be an object of strings"));
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        m.properties().forEach(e -> {
            if (e.getValue().isTextual()) {
                out.put(e.getKey(), e.getValue().textValue());
            } else {
                errors.add(new ConfigError(p + "." + e.getKey(), "must be a string"));
            }
        });
        return out;
    }
}
