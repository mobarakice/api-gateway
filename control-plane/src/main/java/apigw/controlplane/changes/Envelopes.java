package apigw.controlplane.changes;

import apigw.config.etcd.KeyValue;
import apigw.config.worker.EtcdSnapshotBuilder;
import apigw.controlplane.Json;
import apigw.controlplane.api.ApiException;
import apigw.controlplane.store.Kind;
import apigw.model.ConfigError;
import apigw.model.ConfigException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns Admin API bodies into stored envelopes, checks single objects (decode rules only; references
 * and cross-object rules are checked by the dry run), and redacts what must never leave the control
 * plane.
 */
public final class Envelopes {

    /** Same as the config model's object ID rule. */
    public static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Set<String> BODY_FIELDS = Set.of("tenantId", "spec");
    private static final String PROBE_PREFIX = "/probe/";
    private static final Set<String> CERT_FIELDS = Set.of("certRef", "keyRef", "sni");

    private Envelopes() {
    }

    /** The envelope stored for {@code kind}/{@code id} from body {@code {tenantId?, spec}}. */
    public static ObjectNode build(Kind kind, String id, JsonNode body, String actor, Instant now) {
        requireId(id, "id");
        if (body == null || !body.isObject()) {
            throw ApiException.badRequest("body must be a JSON object {\"tenantId\"?, \"spec\"}");
        }
        List<ConfigError> errors = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : body.properties()) {
            if (!BODY_FIELDS.contains(e.getKey())) {
                errors.add(new ConfigError(e.getKey(), "unknown field (allowed: tenantId, spec)"));
            }
        }
        JsonNode spec = body.get("spec");
        if (spec == null || !spec.isObject()) {
            errors.add(new ConfigError("spec", "required: an object"));
        }
        JsonNode tenant = body.get("tenantId");
        if (tenant != null && !tenant.isNull()) {
            if (kind == Kind.SETTINGS) {
                errors.add(new ConfigError("tenantId", "settings are global"));
            } else if (!tenant.isTextual() || !ID.matcher(tenant.asText()).matches()) {
                errors.add(new ConfigError("tenantId", "must match " + ID.pattern()));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        ObjectNode env = Json.MAPPER.createObjectNode().put("id", id).put("kind", kind.kind()).put("schemaVersion", 1);
        if (tenant != null && !tenant.isNull()) {
            env.put("tenantId", tenant.asText());
        }
        env.put("updatedAt", now.toString()).put("updatedBy", actor);
        env.set("spec", spec.deepCopy());
        return env;
    }

    public static void requireId(String id, String what) {
        if (id == null || !ID.matcher(id).matches()) {
            throw ApiException.badRequest(what + " must match " + ID.pattern());
        }
    }

    /**
     * Checks one object the way the gateway would decode it, plus the control plane's own rules (no
     * private keys anywhere, certificate references). Paths are relative keys ({@code routes/a.spec.path}).
     */
    public static List<ConfigError> check(String rel, ObjectNode envelope) {
        List<ConfigError> errors = new ArrayList<>();
        secrets(envelope, rel, errors);
        Kind kind = Kind.ofKey(rel).orElse(null);
        if (kind == Kind.CERTIFICATES) {
            certificate(envelope.path("spec"), rel + ".spec", errors);
        }
        byte[] bytes = Json.bytes(envelope);
        List<KeyValue> kvs = List.of(
                new KeyValue(PROBE_PREFIX + "meta/schema-version", "1".getBytes(StandardCharsets.US_ASCII), 1, 1, 1, 0),
                new KeyValue(PROBE_PREFIX + rel, bytes, 2, 2, 1, 0));
        try {
            EtcdSnapshotBuilder.build(PROBE_PREFIX, kvs, 2);
        } catch (ConfigException e) {
            errors.addAll(e.errors());
        }
        return errors;
    }

    private static void certificate(JsonNode spec, String path, List<ConfigError> errors) {
        for (Map.Entry<String, JsonNode> e : spec.properties()) {
            if (!CERT_FIELDS.contains(e.getKey())) {
                errors.add(new ConfigError(path + "." + e.getKey(), "unknown field (allowed: certRef, keyRef, sni)"));
            }
        }
        for (String f : List.of("certRef", "keyRef")) {
            JsonNode v = spec.get(f);
            if (v == null || !v.isTextual() || v.asText().isBlank() || v.asText().length() > 512) {
                errors.add(new ConfigError(path + "." + f,
                        "required: a reference (file path or secret-manager name, <= 512 chars), never key material"));
            }
        }
        JsonNode sni = spec.get("sni");
        if (sni != null && (!sni.isArray() || sni.size() > 100)) {
            errors.add(new ConfigError(path + ".sni", "must be an array of at most 100 host names"));
        }
    }

    /** Refuses private-key material anywhere in a value: the control plane stores references only. */
    private static void secrets(JsonNode n, String path, List<ConfigError> errors) {
        if (n.isTextual()) {
            String s = n.asText();
            if (s.contains("-----BEGIN") && s.contains("PRIVATE KEY")) {
                errors.add(new ConfigError(path, "must not contain private key material; store a reference"));
            }
        } else if (n.isObject()) {
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                secrets(e.getValue(), path + "." + e.getKey(), errors);
            }
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) {
                secrets(n.get(i), path + "[" + i + "]", errors);
            }
        }
    }

    /**
     * A copy safe to return through the API: credential hashes are removed (the API never returns a
     * stored secret or anything derived from one).
     */
    public static JsonNode redact(String rel, JsonNode envelope) {
        if (envelope == null || !rel.startsWith(Kind.CREDENTIALS.keyPrefix()) || !envelope.isObject()) {
            return envelope;
        }
        ObjectNode copy = envelope.deepCopy();
        if (copy.path("spec").isObject()) {
            ((ObjectNode) copy.get("spec")).remove("hash");
        }
        return copy;
    }
}
