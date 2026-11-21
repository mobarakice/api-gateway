package apigw.plugins.transform;

import apigw.core.plugin.Exchange;
import apigw.core.plugin.Outcome;
import apigw.core.plugin.Phase;
import apigw.core.plugin.Plugin;
import apigw.core.plugin.PluginConfigException;
import apigw.core.plugin.PluginContext;
import apigw.core.plugin.PluginFactory;
import apigw.core.plugin.RequestPlugin;
import apigw.model.ConfigError;
import apigw.plugins.support.Configs;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.MultiMap;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code request-transform}: rewrites what is sent upstream. Config:
 * <pre>{"path": {"stripPrefix": "/api", "addPrefix": "/v2"} | {"template": "/users/{id}/profile"},
 *  "query": {"remove": ["debug"], "rename": {"q": "query"}, "set": {"src": "gw"}, "add": {"tag": "a"}},
 *  "headers": {"rename": {"x-old": "x-new"}}}</pre>
 * Templates substitute the route's path parameters (raw, as matched). Query edits work on raw
 * (still-encoded) keys; configured values are percent-encoded at compile time. Order: path, then query
 * remove, rename, set, add; then header renames. Header set/add/remove belong to the {@code headers} plugin.
 */
public final class RequestTransformPluginFactory implements PluginFactory {

    private static final Set<String> FORBIDDEN_HEADERS = Set.of("connection", "keep-alive", "proxy-connection",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length", "host", "http2-settings",
            "x-consumer-id", "x-consumer-username", "x-tenant-id", "x-authenticated-user",
            "x-credential-identifier", "x-forwarded-for", "forwarded", "x-request-id");

    @Override
    public String name() {
        return "request-transform";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public Set<Phase> phases() {
        return Set.of(Phase.BEFORE_PROXY);
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public String schemaResource() {
        return "schemas/plugins/request-transform.json";
    }

    @Override
    public Plugin create(JsonNode config, PluginContext context) throws PluginConfigException {
        List<ConfigError> errors = new ArrayList<>();
        Configs.known(config, "", Set.of("path", "query", "headers"), errors);
        PathRule path = PathRule.NONE;
        JsonNode p = config.get("path");
        if (p != null && !p.isNull()) {
            Configs.known(p, "path", Set.of("stripPrefix", "addPrefix", "template"), errors);
            String strip = Configs.string(p, "path", "stripPrefix", "", errors);
            String add = Configs.string(p, "path", "addPrefix", "", errors);
            String template = Configs.string(p, "path", "template", "", errors);
            if (!template.isEmpty() && (!strip.isEmpty() || !add.isEmpty())) {
                errors.add(new ConfigError("path", "use either template or stripPrefix/addPrefix"));
            }
            for (String[] f : new String[][] {{"stripPrefix", strip}, {"addPrefix", add}}) {
                if (!f[1].isEmpty() && !pathText(f[1], false)) {
                    errors.add(new ConfigError("path." + f[0], "must start with / and contain only path characters"));
                }
            }
            String[] parts = template.isEmpty() ? new String[0] : templateParts(template, errors);
            path = new PathRule(strip, add, parts);
        }
        QueryRule query = QueryRule.NONE;
        JsonNode q = config.get("query");
        if (q != null && !q.isNull()) {
            Configs.known(q, "query", Set.of("remove", "rename", "set", "add"), errors);
            List<String> remove = Configs.strings(q, "query", "remove", errors);
            for (int i = 0; i < remove.size(); i++) {
                if (!queryKey(remove.get(i))) {
                    errors.add(new ConfigError("query.remove[" + i + "]", "must be unreserved characters only"));
                }
            }
            query = new QueryRule(Set.copyOf(remove), pairs(q, "query.rename", "rename", false, errors),
                    pairs(q, "query.set", "set", true, errors), pairs(q, "query.add", "add", true, errors));
        }
        Map<String, String> renames = new LinkedHashMap<>();
        JsonNode h = config.get("headers");
        if (h != null && !h.isNull()) {
            Configs.known(h, "headers", Set.of("rename"), errors);
            JsonNode r = h.get("rename");
            if (r != null && r.isObject()) {
                for (Map.Entry<String, JsonNode> e : r.properties()) {
                    String from = e.getKey().toLowerCase(Locale.ROOT);
                    String to = e.getValue().isTextual() ? e.getValue().textValue().toLowerCase(Locale.ROOT) : "";
                    if (!Configs.token(from) || !Configs.token(to) || FORBIDDEN_HEADERS.contains(from)
                            || FORBIDDEN_HEADERS.contains(to)) {
                        errors.add(new ConfigError("headers.rename." + e.getKey(),
                                "both names must be valid, non-framing, non-identity headers"));
                    } else {
                        renames.put(from, to);
                    }
                }
            } else if (r != null) {
                errors.add(new ConfigError("headers.rename", "must be an object of old name to new name"));
            }
        }
        if (errors.isEmpty() && path == PathRule.NONE && query == QueryRule.NONE && renames.isEmpty()) {
            errors.add(new ConfigError("", "configure at least one of path, query or headers"));
        }
        if (!errors.isEmpty()) {
            throw new PluginConfigException(errors);
        }
        return new Transform(path, query, renames.entrySet().stream().map(e -> new String[] {e.getKey(),
                e.getValue()}).toArray(String[][]::new));
    }

    private static Map<String, String> pairs(JsonNode q, String path, String field, boolean encodeValues,
            List<ConfigError> errors) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode n = q.get(field);
        if (n == null || n.isNull()) {
            return out;
        }
        if (!n.isObject()) {
            errors.add(new ConfigError(path, "must be an object"));
            return out;
        }
        for (Map.Entry<String, JsonNode> e : n.properties()) {
            if (!queryKey(e.getKey()) || !e.getValue().isTextual()) {
                errors.add(new ConfigError(path + "." + e.getKey(),
                        "keys must be unreserved characters; values strings"));
                continue;
            }
            String v = e.getValue().textValue();
            if (!encodeValues && !queryKey(v)) {
                errors.add(new ConfigError(path + "." + e.getKey(), "must be unreserved characters only"));
                continue;
            }
            out.put(e.getKey(), encodeValues ? encode(v) : v);
        }
        return out;
    }

    private static String[] templateParts(String template, List<ConfigError> errors) {
        // Alternating literal, param, literal, ...; params are {name}.
        List<String> parts = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i);
                if (end < 0) {
                    errors.add(new ConfigError("path.template", "unclosed {"));
                    return new String[0];
                }
                parts.add(lit.toString());
                lit.setLength(0);
                parts.add(template.substring(i + 1, end));
                i = end;
            } else {
                lit.append(c);
            }
        }
        parts.add(lit.toString());
        if (!template.startsWith("/") || !pathText(String.join("", parts), true)) {
            errors.add(new ConfigError("path.template", "must start with / and contain only path characters"));
        }
        return parts.toArray(String[]::new);
    }

    private static boolean pathText(String s, boolean allowEmptySegments) {
        if (!s.startsWith("/") && !allowEmptySegments) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || "-._~!$&'()*+,;=:@/%".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return !s.contains("/../") && !s.endsWith("/..") && !s.contains("/./");
    }

    private static boolean queryKey(String s) {
        if (s.isEmpty() || s.length() > 256) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "-._~".indexOf(c) >= 0)) {
                return false;
            }
        }
        return true;
    }

    /** RFC 3986 percent-encoding of everything but unreserved characters. */
    static String encode(String v) {
        StringBuilder b = new StringBuilder(v.length() + 8);
        for (byte x : v.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "-._~".indexOf(c) >= 0) {
                b.append((char) c);
            } else {
                b.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return b.toString();
    }

    record PathRule(String strip, String add, String[] template) {
        static final PathRule NONE = new PathRule("", "", new String[0]);
    }

    record QueryRule(Set<String> remove, Map<String, String> rename, Map<String, String> set,
            Map<String, String> add) {
        static final QueryRule NONE = new QueryRule(Set.of(), Map.of(), Map.of(), Map.of());
    }

    record Transform(PathRule path, QueryRule query, String[][] renames) implements RequestPlugin {

        @Override
        public Outcome onRequest(Exchange exchange) {
            if (path != PathRule.NONE || query != QueryRule.NONE) {
                String uri = exchange.upstreamUri();
                int qi = uri.indexOf('?');
                String p = qi < 0 ? uri : uri.substring(0, qi);
                String q = qi < 0 ? null : uri.substring(qi + 1);
                if (path != PathRule.NONE) {
                    p = rewritePath(exchange, p);
                }
                if (query != QueryRule.NONE) {
                    q = rewriteQuery(q);
                }
                exchange.upstreamUri(q == null || q.isEmpty() ? p : p + "?" + q);
            }
            if (renames.length > 0) {
                MultiMap h = exchange.upstreamHeaders();
                for (String[] r : renames) {
                    List<String> values = h.getAll(r[0]);
                    if (!values.isEmpty()) {
                        h.remove(r[0]);
                        h.remove(r[1]);
                        h.add(r[1], values);
                    }
                }
            }
            return Outcome.CONTINUE;
        }

        private String rewritePath(Exchange exchange, String p) {
            if (path.template().length > 0) {
                String[] t = path.template();
                StringBuilder b = new StringBuilder(64).append(t[0]);
                for (int i = 1; i < t.length; i += 2) {
                    String v = exchange.pathParam(t[i]);
                    b.append(v == null ? "" : v).append(t[i + 1]);
                }
                return b.toString();
            }
            String out = p;
            if (!path.strip().isEmpty() && (out.equals(path.strip()) || out.startsWith(path.strip())
                    && (path.strip().endsWith("/") || out.charAt(path.strip().length()) == '/'))) {
                out = out.substring(path.strip().length());
                if (out.isEmpty() || out.charAt(0) != '/') {
                    out = "/" + out;
                }
            }
            if (!path.add().isEmpty()) {
                out = path.add().endsWith("/") && out.startsWith("/") ? path.add() + out.substring(1)
                        : out.equals("/") ? path.add() : path.add() + out;
            }
            return out;
        }

        private String rewriteQuery(String q) {
            StringBuilder b = new StringBuilder(q == null ? 32 : q.length() + 32);
            if (q != null && !q.isEmpty()) {
                int start = 0;
                while (start <= q.length()) {
                    int amp = q.indexOf('&', start);
                    int end = amp < 0 ? q.length() : amp;
                    if (end > start) {
                        int eq = q.indexOf('=', start);
                        String key = eq < 0 || eq > end ? q.substring(start, end) : q.substring(start, eq);
                        String rest = eq < 0 || eq > end ? "" : q.substring(eq, end);
                        if (!query.remove().contains(key) && !query.set().containsKey(key)) {
                            String renamed = query.rename().get(key);
                            if (renamed == null || !query.set().containsKey(renamed)) {
                                append(b, renamed != null ? renamed : key, rest);
                            }
                        }
                    }
                    if (amp < 0) {
                        break;
                    }
                    start = amp + 1;
                }
            }
            for (Map.Entry<String, String> e : query.set().entrySet()) {
                append(b, e.getKey(), "=" + e.getValue());
            }
            for (Map.Entry<String, String> e : query.add().entrySet()) {
                append(b, e.getKey(), "=" + e.getValue());
            }
            return b.toString();
        }

        private static void append(StringBuilder b, String key, String rest) {
            if (!b.isEmpty()) {
                b.append('&');
            }
            b.append(key).append(rest);
        }
    }
}
