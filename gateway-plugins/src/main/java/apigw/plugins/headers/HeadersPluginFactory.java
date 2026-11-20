package apigw.plugins.headers;

import apigw.core.plugin.Phase;
import apigw.core.plugin.Plugin;
import apigw.core.plugin.PluginConfigException;
import apigw.core.plugin.PluginFactory;
import apigw.model.ConfigError;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code headers}: static header edits. Config (schema {@code schemas/plugins/headers.json}):
 * <pre>{"request":  {"set": {name: value}, "add": {name: value}, "remove": [name]},
 *  "response": {"set": {...}, "add": {...}, "remove": [...]}}</pre>
 * Request edits apply in BEFORE_PROXY to the headers sent upstream; response edits in HEADER_FILTER.
 * Order within a side: remove, then set, then add. Framing and hop-by-hop headers cannot be edited.
 */
public final class HeadersPluginFactory implements PluginFactory {

    static final Set<String> FORBIDDEN = Set.of("connection", "keep-alive", "proxy-connection", "te", "trailer",
            "transfer-encoding", "upgrade", "content-length", "host", "http2-settings");

    @Override
    public String name() {
        return "headers";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public Set<Phase> phases() {
        return Set.of(Phase.BEFORE_PROXY, Phase.HEADER_FILTER);
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public String schemaResource() {
        return "schemas/plugins/headers.json";
    }

    @Override
    public Plugin create(JsonNode config, apigw.core.plugin.PluginContext context) throws PluginConfigException {
        List<ConfigError> errors = new ArrayList<>();
        unknown(config, "", Set.of("request", "response"), errors);
        HeaderEdits request = edits(config.get("request"), "request", errors);
        HeaderEdits response = edits(config.get("response"), "response", errors);
        if (!errors.isEmpty()) {
            throw new PluginConfigException(errors);
        }
        if (request.isEmpty() && response.isEmpty()) {
            throw new PluginConfigException("", "configure at least one of request or response edits");
        }
        if (response.isEmpty()) {
            return new HeadersPlugins.RequestOnly(request);
        }
        if (request.isEmpty()) {
            return new HeadersPlugins.ResponseOnly(response);
        }
        return new HeadersPlugins.Both(request, response);
    }

    private static HeaderEdits edits(JsonNode n, String path, List<ConfigError> errors) {
        if (n == null || n.isNull()) {
            return HeaderEdits.NONE;
        }
        if (!n.isObject()) {
            errors.add(new ConfigError(path, "must be an object"));
            return HeaderEdits.NONE;
        }
        unknown(n, path + ".", Set.of("set", "add", "remove"), errors);
        Map<String, String> set = pairs(n.get("set"), path + ".set", errors);
        Map<String, String> add = pairs(n.get("add"), path + ".add", errors);
        List<String> remove = new ArrayList<>();
        JsonNode r = n.get("remove");
        if (r != null && !r.isNull()) {
            if (!r.isArray()) {
                errors.add(new ConfigError(path + ".remove", "must be an array of header names"));
            } else {
                for (int i = 0; i < r.size(); i++) {
                    String p = path + ".remove[" + i + "]";
                    if (!r.get(i).isTextual()) {
                        errors.add(new ConfigError(p, "must be a string"));
                    } else if (name(r.get(i).textValue(), p, errors)) {
                        remove.add(r.get(i).textValue().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return new HeaderEdits(set, add, remove);
    }

    private static Map<String, String> pairs(JsonNode n, String path, List<ConfigError> errors) {
        Map<String, String> out = new LinkedHashMap<>();
        if (n == null || n.isNull()) {
            return out;
        }
        if (!n.isObject()) {
            errors.add(new ConfigError(path, "must be an object of header name to value"));
            return out;
        }
        for (Map.Entry<String, JsonNode> e : n.properties()) {
            String p = path + "." + e.getKey();
            if (!name(e.getKey(), p, errors)) {
                continue;
            }
            if (!e.getValue().isTextual()) {
                errors.add(new ConfigError(p, "must be a string"));
                continue;
            }
            String v = e.getValue().textValue();
            if (!validValue(v)) {
                errors.add(new ConfigError(p, "value must be visible ASCII, space or tab (no CR/LF)"));
                continue;
            }
            out.put(e.getKey().toLowerCase(Locale.ROOT), v);
        }
        return out;
    }

    private static boolean name(String name, String path, List<ConfigError> errors) {
        if (!token(name)) {
            errors.add(new ConfigError(path, "not a valid header name"));
            return false;
        }
        if (FORBIDDEN.contains(name.toLowerCase(Locale.ROOT))) {
            errors.add(new ConfigError(path, "header '" + name + "' cannot be edited (framing/hop-by-hop)"));
            return false;
        }
        return true;
    }

    private static boolean token(String s) {
        if (s.isEmpty() || s.length() > 256) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean validValue(String v) {
        if (v.length() > 8192) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c != '\t' && (c < 0x20 || c > 0x7e)) {
                return false;
            }
        }
        return true;
    }

    private static void unknown(JsonNode n, String prefix, Set<String> allowed, List<ConfigError> errors) {
        if (n == null || !n.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> e : n.properties()) {
            if (!allowed.contains(e.getKey())) {
                errors.add(new ConfigError(prefix + e.getKey(), "unknown field"));
            }
        }
    }
}
