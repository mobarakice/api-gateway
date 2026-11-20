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

    public static Consumer decodeConsumer(JsonNode node, String path) throws ConfigException {
        ConfigDecoder d = new ConfigDecoder();
        Consumer c = d.consumer(node, path);
        d.throwIfErrors();
        return c;
    }

    public static Credential decodeCredential(JsonNode node, String path) throws ConfigException {
        ConfigDecoder d = new ConfigDecoder();
        Credential c = d.credential(node, path);
        d.throwIfErrors();
        return c;
    }

    public static AuthzPolicy decodeAuthzPolicy(JsonNode node, String path) throws ConfigException {
        ConfigDecoder d = new ConfigDecoder();
        AuthzPolicy c = d.authzPolicy(node, path);
        d.throwIfErrors();
        return c;
    }

    public static PluginConfigSet decodePluginConfig(JsonNode node, String path) throws ConfigException {
        ConfigDecoder d = new ConfigDecoder();
        PluginConfigSet c = d.pluginConfig(node, path);
        d.throwIfErrors();
        return c;
    }

    public static RevokedToken decodeRevokedToken(JsonNode node, String path) throws ConfigException {
        ConfigDecoder d = new ConfigDecoder();
        RevokedToken c = d.revokedToken(node, path);
        d.throwIfErrors();
        return c;
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
                "globalPlugins", "consumers", "credentials", "authzPolicies", "pluginConfigs", "revokedTokens"));
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
        List<Consumer> consumers = new ArrayList<>();
        each(n, "$", "consumers", (item, p) -> consumers.add(consumer(item, p)));
        List<Credential> credentials = new ArrayList<>();
        each(n, "$", "credentials", (item, p) -> credentials.add(credential(item, p)));
        List<AuthzPolicy> policies = new ArrayList<>();
        each(n, "$", "authzPolicies", (item, p) -> policies.add(authzPolicy(item, p)));
        List<PluginConfigSet> pluginConfigs = new ArrayList<>();
        each(n, "$", "pluginConfigs", (item, p) -> pluginConfigs.add(pluginConfig(item, p)));
        List<RevokedToken> revoked = new ArrayList<>();
        each(n, "$", "revokedTokens", (item, p) -> revoked.add(revokedToken(item, p)));
        if (!errors.isEmpty()) {
            return null;
        }
        return new GatewayConfig(schemaVersion, revision, settings, routes, services, upstreams, global, consumers,
                credentials, policies, pluginConfigs, revoked);
    }

    private Settings settings(JsonNode n, String path) {
        if (!object(n, path)) {
            return Settings.DEFAULTS;
        }
        known(n, path, Set.of("trustedProxies", "stripRequestHeaders", "viaName", "defaultTimeouts",
                "maxRequestBodyBytes", "upstreamAllowCidrs"));
        Settings d = Settings.DEFAULTS;
        return new Settings(
                n.has("trustedProxies") ? strings(n, path, "trustedProxies") : d.trustedProxies(),
                n.has("stripRequestHeaders")
                        ? strings(n, path, "stripRequestHeaders").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList()
                        : d.stripRequestHeaders(),
                stringField(n, path, "viaName", d.viaName()),
                n.has("defaultTimeouts") ? timeouts(n.get("defaultTimeouts"), path + ".defaultTimeouts")
                        .orElse(Timeouts.DEFAULTS) : d.defaultTimeouts(),
                longField(n, path, "maxRequestBodyBytes", d.maxRequestBodyBytes()),
                n.has("upstreamAllowCidrs") ? strings(n, path, "upstreamAllowCidrs") : d.upstreamAllowCidrs());
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
                boolField(n, path, "preserveHost", false),
                tenant(n, path));
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
                plugins(n, path, "plugins"),
                tenant(n, path));
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
                boolField(n, path, "http2", false),
                tenant(n, path));
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
        known(item, p, Set.of("name", "enabled", "config", "configRef"));
        JsonNode config = item.has("config") ? item.get("config") : MAPPER.createObjectNode();
        if (!config.isObject()) {
            errors.add(new ConfigError(p + ".config", "must be an object"));
        }
        String ref = stringField(item, p, "configRef", "");
        if (!ref.isEmpty() && item.has("config")) {
            errors.add(new ConfigError(p + ".configRef", "use either config or configRef, not both"));
        }
        return new PluginRef(stringField(item, p, "name", null), boolField(item, p, "enabled", true),
                config.deepCopy(), ref.isEmpty() ? null : ref);
    }

    private String tenant(JsonNode n, String path) {
        String t = stringField(n, path, "tenantId", "");
        return t.isEmpty() ? null : t;
    }

    private static final Set<String> ENVELOPE_FIELDS = Set.of("kind", "schemaVersion", "updatedAt", "updatedBy");

    private Set<String> withEnvelope(String... fields) {
        Set<String> s = new java.util.HashSet<>(ENVELOPE_FIELDS);
        s.addAll(List.of(fields));
        return s;
    }

    private Consumer consumer(JsonNode n, String path) {
        if (!object(n, path)) {
            return null;
        }
        known(n, path, withEnvelope("id", "tenantId", "username", "roles", "attributes", "plugins"));
        return new Consumer(stringField(n, path, "id", null), tenant(n, path), stringField(n, path, "username", ""),
                n.has("roles") ? strings(n, path, "roles") : List.of(), stringMap(n, path, "attributes"),
                plugins(n, path, "plugins"));
    }

    private Credential credential(JsonNode n, String path) {
        if (!object(n, path)) {
            return null;
        }
        known(n, path, withEnvelope("id", "consumerId", "tenantId", "type", "keyId", "hash"));
        return new Credential(stringField(n, path, "id", null), stringField(n, path, "consumerId", null),
                tenant(n, path), enumField(n, path, "type", Credential.Type.class, null),
                stringField(n, path, "keyId", null), stringField(n, path, "hash", null));
    }

    private AuthzPolicy authzPolicy(JsonNode n, String path) {
        if (!object(n, path)) {
            return null;
        }
        known(n, path, withEnvelope("id", "tenantId", "effect", "routes", "methods", "subjects", "condition"));
        AuthzPolicy.Subjects subjects = AuthzPolicy.Subjects.ANYONE;
        if (n.has("subjects") && object(n.get("subjects"), path + ".subjects")) {
            JsonNode s = n.get("subjects");
            String sp = path + ".subjects";
            known(s, sp, Set.of("roles", "consumers", "anyAuthenticated"));
            subjects = new AuthzPolicy.Subjects(s.has("roles") ? strings(s, sp, "roles") : List.of(),
                    s.has("consumers") ? strings(s, sp, "consumers") : List.of(),
                    boolField(s, sp, "anyAuthenticated", false));
        }
        Condition condition = n.has("condition") ? condition(n.get("condition"), path + ".condition", 0) : null;
        return new AuthzPolicy(stringField(n, path, "id", null), tenant(n, path),
                enumField(n, path, "effect", AuthzPolicy.Effect.class, null),
                n.has("routes") ? strings(n, path, "routes") : List.of(),
                n.has("methods") ? strings(n, path, "methods") : List.of(), subjects, condition);
    }

    private static final int MAX_CONDITION_DEPTH = 16;

    private Condition condition(JsonNode n, String path, int depth) {
        if (!object(n, path)) {
            return null;
        }
        if (depth > MAX_CONDITION_DEPTH) {
            errors.add(new ConfigError(path, "nested deeper than " + MAX_CONDITION_DEPTH));
            return null;
        }
        if (n.has("all") || n.has("any")) {
            String f = n.has("all") ? "all" : "any";
            known(n, path, Set.of(f));
            List<Condition> children = new ArrayList<>();
            each(n, path, f, (item, p) -> children.add(condition(item, p, depth + 1)));
            if (children.isEmpty()) {
                errors.add(new ConfigError(path + "." + f, "must not be empty"));
            }
            return "all".equals(f) ? new Condition.All(children) : new Condition.Any(children);
        }
        if (n.has("not")) {
            known(n, path, Set.of("not"));
            return new Condition.Not(condition(n.get("not"), path + ".not", depth + 1));
        }
        known(n, path, Set.of("op", "attr", "value", "values", "ref"));
        Condition.Op op = enumField(n, path, "op", Condition.Op.class, null);
        String attr = stringField(n, path, "attr", null);
        if (attr != null && !Condition.ATTRIBUTE.matcher(attr).matches()) {
            errors.add(new ConfigError(path + ".attr", "unknown attribute '" + attr + "'"));
        }
        String value = stringField(n, path, "value", "");
        List<String> values = n.has("values") ? strings(n, path, "values") : List.of();
        String ref = stringField(n, path, "ref", "");
        if (!ref.isEmpty() && !Condition.ATTRIBUTE.matcher(ref).matches()) {
            errors.add(new ConfigError(path + ".ref", "unknown attribute '" + ref + "'"));
        }
        if (op != null) {
            boolean hasValue = n.has("value");
            boolean hasValues = n.has("values");
            boolean hasRef = !ref.isEmpty();
            int forms = (hasValue ? 1 : 0) + (hasValues ? 1 : 0) + (hasRef ? 1 : 0);
            boolean ok = switch (op) {
                case EXISTS -> forms == 0;
                case IN -> hasValues && forms == 1;
                case PREFIX -> hasValue && forms == 1;
                case EQ, NE, CONTAINS -> (hasValue || hasRef) && forms == 1;
            };
            if (!ok) {
                errors.add(new ConfigError(path, "operand does not fit op " + op.name().toLowerCase(Locale.ROOT)
                        + " (exists: none; in: values; prefix: value; eq/ne/contains: value or ref)"));
            }
        }
        return new Condition.Compare(op, attr, n.has("value") ? value : null, values, ref.isEmpty() ? null : ref);
    }

    private PluginConfigSet pluginConfig(JsonNode n, String path) {
        if (!object(n, path)) {
            return null;
        }
        known(n, path, withEnvelope("id", "tenantId", "plugin", "config"));
        JsonNode config = n.has("config") ? n.get("config") : MAPPER.createObjectNode();
        if (!config.isObject()) {
            errors.add(new ConfigError(path + ".config", "must be an object"));
        }
        return new PluginConfigSet(stringField(n, path, "id", null), stringField(n, path, "plugin", null),
                config.deepCopy());
    }

    private RevokedToken revokedToken(JsonNode n, String path) {
        if (!object(n, path)) {
            return null;
        }
        known(n, path, withEnvelope("id", "jti", "issuer", "expiresAt"));
        String exp = stringField(n, path, "expiresAt", null);
        java.time.Instant expiresAt = null;
        if (exp != null) {
            try {
                expiresAt = java.time.Instant.parse(exp);
            } catch (java.time.format.DateTimeParseException e) {
                errors.add(new ConfigError(path + ".expiresAt", "must be an RFC 3339 UTC timestamp"));
            }
        }
        String issuer = stringField(n, path, "issuer", "");
        return new RevokedToken(stringField(n, path, "id", null), stringField(n, path, "jti", null),
                issuer.isEmpty() ? null : issuer, expiresAt);
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
