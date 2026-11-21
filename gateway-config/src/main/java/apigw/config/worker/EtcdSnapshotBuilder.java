package apigw.config.worker;

import apigw.config.etcd.KeyValue;
import apigw.model.AuthzPolicy;
import apigw.model.CachePurge;
import apigw.model.ConfigDecoder;
import apigw.model.Consumer;
import apigw.model.Credential;
import apigw.model.PluginConfigSet;
import apigw.model.RevokedToken;
import apigw.model.ConfigError;
import apigw.model.ConfigException;
import apigw.model.GatewayConfig;
import apigw.model.PluginRef;
import apigw.model.Route;
import apigw.model.Service;
import apigw.model.Settings;
import apigw.model.Upstream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a {@link GatewayConfig} from the config keys under one prefix, all read at one revision
 * (see {@code docs/architecture/etcd-keys.md}). Every value is an envelope
 * {@code {id, kind, schemaVersion, tenantId?, updatedAt, updatedBy, spec}}; the key's last segment
 * must equal {@code id}. Errors are reported against etcd keys ({@code routes/orders.spec.path}).
 */
public final class EtcdSnapshotBuilder {

    /** Subtrees that make up the gateway's configuration; everything else under the prefix is ignored. */
    public static final List<String> CONFIG_SUBTREES = List.of("meta/", "routes/", "services/", "upstreams/",
            "global-plugins/", "consumers/", "credentials/", "plugin-configs/", "policies/", "certificates/");

    /** Subtrees read and kept (LKG, change detection) but not compiled yet: certificates arrive with TLS reload. */
    static final Set<String> NOT_YET_COMPILED = Set.of("certificates/");

    static final String SCHEMA_VERSION_KEY = "meta/schema-version";
    static final String SETTINGS_KEY = "meta/settings";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ENVELOPE = Set.of("id", "kind", "schemaVersion", "tenantId", "updatedAt",
            "updatedBy", "spec");
    private static final Pattern INDEXED = Pattern.compile(
            "^(routes|services|upstreams|globalPlugins|consumers|credentials|authzPolicies|pluginConfigs"
                    + "|revokedTokens|cachePurges)\\[(\\d+)]");

    private EtcdSnapshotBuilder() {
    }

    /** True if {@code key} (absolute) is part of the gateway configuration under {@code prefix}. */
    public static boolean isConfigKey(String prefix, String key) {
        if (!key.startsWith(prefix)) {
            return false;
        }
        for (String sub : CONFIG_SUBTREES) {
            if (key.startsWith(sub, prefix.length())) {
                return true;
            }
        }
        return false;
    }

    public static GatewayConfig build(String prefix, Collection<KeyValue> kvs, long revision) throws ConfigException {
        List<ConfigError> errors = new ArrayList<>();
        List<Route> routes = new ArrayList<>();
        List<Service> services = new ArrayList<>();
        List<Upstream> upstreams = new ArrayList<>();
        List<PluginRef> global = new ArrayList<>();
        List<Consumer> consumers = new ArrayList<>();
        List<Credential> credentials = new ArrayList<>();
        List<AuthzPolicy> policies = new ArrayList<>();
        List<PluginConfigSet> pluginConfigs = new ArrayList<>();
        List<RevokedToken> revoked = new ArrayList<>();
        List<CachePurge> purges = new ArrayList<>();
        Settings settings = Settings.DEFAULTS;
        Integer schemaVersion = null;
        List<KeyValue> sorted = new ArrayList<>(kvs);
        sorted.sort(Comparator.comparing(KeyValue::key));
        for (KeyValue kv : sorted) {
            if (!isConfigKey(prefix, kv.key())) {
                continue;
            }
            String rel = kv.key().substring(prefix.length());
            try {
                if (SCHEMA_VERSION_KEY.equals(rel)) {
                    schemaVersion = schemaVersion(kv, rel);
                } else if (SETTINGS_KEY.equals(rel)) {
                    settings = ConfigDecoder.decodeSettings(spec(kv, rel, "Settings", "settings"), rel + ".spec");
                } else if (rel.startsWith("routes/")) {
                    routes.add(ConfigDecoder.decodeRoute(specWithId(kv, rel, "Route", "id"), rel + ".spec"));
                } else if (rel.startsWith("services/")) {
                    services.add(ConfigDecoder.decodeService(specWithId(kv, rel, "Service", "id"), rel + ".spec"));
                } else if (rel.startsWith("upstreams/")) {
                    upstreams.add(ConfigDecoder.decodeUpstream(specWithId(kv, rel, "Upstream", "id"),
                            rel + ".spec"));
                } else if (rel.startsWith("global-plugins/")) {
                    global.add(ConfigDecoder.decodePluginRef(specWithId(kv, rel, "GlobalPlugin", "name"),
                            rel + ".spec"));
                } else if (rel.startsWith("consumers/")) {
                    consumers.add(ConfigDecoder.decodeConsumer(specWithId(kv, rel, "Consumer", "id"), rel + ".spec"));
                } else if (rel.startsWith("credentials/")) {
                    credentials.add(credential(kv, rel));
                } else if (rel.startsWith("plugin-configs/")) {
                    pluginConfigs.add(ConfigDecoder.decodePluginConfig(specWithId(kv, rel, "PluginConfig", "id"),
                            rel + ".spec"));
                } else if (rel.startsWith("policies/authz/")) {
                    policies.add(ConfigDecoder.decodeAuthzPolicy(specWithId(kv, rel, "AuthzPolicy", "id"),
                            rel + ".spec"));
                } else if (rel.startsWith("policies/revoked-tokens/")) {
                    revoked.add(ConfigDecoder.decodeRevokedToken(specWithId(kv, rel, "RevokedToken", "id"),
                            rel + ".spec"));
                } else if (rel.startsWith("policies/cache-purge/")) {
                    purges.add(ConfigDecoder.decodeCachePurge(specWithId(kv, rel, "CachePurge", "id"),
                            rel + ".spec"));
                } else if (rel.startsWith("policies/")) {
                    errors.add(new ConfigError(rel, "unknown policy type (expected policies/authz/, "
                            + "policies/revoked-tokens/ or policies/cache-purge/)"));
                } else if (notYetCompiled(rel)) {
                    envelope(kv, rel);
                }
            } catch (ConfigException e) {
                errors.addAll(e.errors());
            }
        }
        if (schemaVersion == null) {
            errors.add(new ConfigError(SCHEMA_VERSION_KEY, "missing: the keyspace is not initialised"));
        }
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
        return new GatewayConfig(schemaVersion, revision, settings, routes, services, upstreams, global, consumers,
                credentials, policies, pluginConfigs, revoked, purges);
    }

    private static boolean notYetCompiled(String rel) {
        for (String sub : NOT_YET_COMPILED) {
            if (rel.startsWith(sub)) {
                return true;
            }
        }
        return false;
    }

    private static int schemaVersion(KeyValue kv, String rel) throws ConfigException {
        String v = kv.valueUtf8().trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = v.substring(1, v.length() - 1);
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new ConfigException(List.of(new ConfigError(rel, "must be an integer")));
        }
    }

    /** Parses and checks the envelope; returns it. */
    private static JsonNode envelope(KeyValue kv, String rel) throws ConfigException {
        JsonNode n;
        try {
            n = MAPPER.readTree(kv.value());
        } catch (IOException e) {
            throw new ConfigException(List.of(new ConfigError(rel, "not valid JSON")));
        }
        if (n == null || !n.isObject()) {
            throw new ConfigException(List.of(new ConfigError(rel, "must be a JSON object envelope")));
        }
        List<ConfigError> errors = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : n.properties()) {
            if (!ENVELOPE.contains(e.getKey())) {
                errors.add(new ConfigError(rel + "." + e.getKey(), "unknown envelope field"));
            }
        }
        JsonNode sv = n.get("schemaVersion");
        if (sv == null || !sv.canConvertToInt() || sv.intValue() < 1
                || sv.intValue() > GatewayConfig.SUPPORTED_SCHEMA_VERSION) {
            errors.add(new ConfigError(rel + ".schemaVersion", "must be an integer between 1 and "
                    + GatewayConfig.SUPPORTED_SCHEMA_VERSION));
        }
        if (!n.path("spec").isObject()) {
            errors.add(new ConfigError(rel + ".spec", "must be an object"));
        }
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
        return n;
    }

    private static JsonNode spec(KeyValue kv, String rel, String kind, String id) throws ConfigException {
        JsonNode env = envelope(kv, rel);
        checkKindAndId(env, rel, kind, id);
        return env.get("spec");
    }

    /** {@code credentials/{consumerId}/{id}}: the consumer comes from the key too. */
    private static Credential credential(KeyValue kv, String rel) throws ConfigException {
        String[] parts = rel.split("/", -1);
        if (parts.length != 3 || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new ConfigException(List.of(new ConfigError(rel, "must be credentials/{consumerId}/{id}")));
        }
        ObjectNode spec = specWithId(kv, rel, "Credential", "id", parts[2]);
        if (spec.has("consumerId")) {
            throw new ConfigException(List.of(new ConfigError(rel + ".spec.consumerId",
                    "comes from the key; do not repeat it in spec")));
        }
        spec.put("consumerId", parts[1]);
        return ConfigDecoder.decodeCredential(spec, rel + ".spec");
    }

    private static ObjectNode specWithId(KeyValue kv, String rel, String kind, String idField)
            throws ConfigException {
        return specWithId(kv, rel, kind, idField, rel.substring(rel.lastIndexOf('/') + 1));
    }

    /**
     * The spec with the key's id injected under {@code idField} and the envelope's {@code tenantId} (if any)
     * under {@code tenantId}, after checking kind and id.
     */
    private static ObjectNode specWithId(KeyValue kv, String rel, String kind, String idField, String id)
            throws ConfigException {
        JsonNode env = envelope(kv, rel);
        checkKindAndId(env, rel, kind, id);
        ObjectNode spec = ((ObjectNode) env.get("spec")).deepCopy();
        if (spec.has(idField)) {
            throw new ConfigException(List.of(new ConfigError(rel + ".spec." + idField,
                    "comes from the key; do not repeat it in spec")));
        }
        spec.put(idField, id);
        if (spec.has("tenantId")) {
            throw new ConfigException(List.of(new ConfigError(rel + ".spec.tenantId",
                    "comes from the envelope's tenantId; do not repeat it in spec")));
        }
        if (env.hasNonNull("tenantId")) {
            spec.put("tenantId", env.get("tenantId").asText());
        }
        return spec;
    }

    private static void checkKindAndId(JsonNode env, String rel, String kind, String id) throws ConfigException {
        List<ConfigError> errors = new ArrayList<>();
        if (!kind.equals(env.path("kind").asText())) {
            errors.add(new ConfigError(rel + ".kind", "must be '" + kind + "' for this key"));
        }
        if (!id.equals(env.path("id").asText())) {
            errors.add(new ConfigError(rel + ".id", "must equal the key's last segment '" + id + "'"));
        }
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
    }

    /**
     * Rewrites validator/compiler paths that use list indexes ({@code routes[3].path}) to the etcd
     * key of that object ({@code routes/orders.path}), so operators see which key to fix.
     */
    public static List<ConfigError> toKeyPaths(List<ConfigError> errors, GatewayConfig config) {
        List<ConfigError> out = new ArrayList<>(errors.size());
        for (ConfigError e : errors) {
            Matcher m = INDEXED.matcher(e.path());
            if (!m.find()) {
                out.add(e);
                continue;
            }
            int i = Integer.parseInt(m.group(2));
            String key = switch (m.group(1)) {
                case "routes" -> i < config.routes().size() ? "routes/" + config.routes().get(i).id() : null;
                case "services" -> i < config.services().size() ? "services/" + config.services().get(i).id() : null;
                case "upstreams" -> i < config.upstreams().size()
                        ? "upstreams/" + config.upstreams().get(i).id() : null;
                case "consumers" -> i < config.consumers().size()
                        ? "consumers/" + config.consumers().get(i).id() : null;
                case "credentials" -> i < config.credentials().size() ? "credentials/"
                        + config.credentials().get(i).consumerId() + "/" + config.credentials().get(i).id() : null;
                case "authzPolicies" -> i < config.authzPolicies().size()
                        ? "policies/authz/" + config.authzPolicies().get(i).id() : null;
                case "pluginConfigs" -> i < config.pluginConfigs().size()
                        ? "plugin-configs/" + config.pluginConfigs().get(i).id() : null;
                case "revokedTokens" -> i < config.revokedTokens().size()
                        ? "policies/revoked-tokens/" + config.revokedTokens().get(i).id() : null;
                case "cachePurges" -> i < config.cachePurges().size()
                        ? "policies/cache-purge/" + config.cachePurges().get(i).id() : null;
                default -> i < config.globalPlugins().size()
                        ? "global-plugins/" + config.globalPlugins().get(i).name() : null;
            };
            out.add(key == null ? e : new ConfigError(key + e.path().substring(m.end()), e.message()));
        }
        return out;
    }
}
