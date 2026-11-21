package apigw.plugins.cors;

import apigw.core.plugin.Exchange;
import apigw.core.plugin.Outcome;
import apigw.core.plugin.Phase;
import apigw.core.plugin.Plugin;
import apigw.core.plugin.PluginConfigException;
import apigw.core.plugin.PluginContext;
import apigw.core.plugin.PluginFactory;
import apigw.core.plugin.RequestPlugin;
import apigw.core.plugin.ResponseHeadersPlugin;
import apigw.model.ConfigError;
import apigw.plugins.support.Configs;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code cors}: answers preflights at the gateway and adds CORS headers to actual responses
 * (including gateway errors, so a browser can read a 401 or 429). Config:
 * <pre>{"allowOrigins": ["https://app.example.com", "https://*.example.com"], "allowMethods": ["GET","POST"],
 *  "allowHeaders": ["authorization","content-type"], "exposeHeaders": ["x-request-id"],
 *  "allowCredentials": false, "maxAgeSeconds": 600}</pre>
 * {@code "*"} allows any origin and cannot be combined with credentials. {@code https://*.example.com}
 * allows any subdomain (not the apex). The origin is echoed, never reflected unchecked.
 */
public final class CorsPluginFactory implements PluginFactory {

    private static final Set<String> SAFELISTED = Set.of("accept", "accept-language", "content-language",
            "content-type", "range");

    @Override
    public String name() {
        return "cors";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public Set<Phase> phases() {
        return Set.of(Phase.ACCESS, Phase.HEADER_FILTER);
    }

    /** Before authentication: preflights carry no credentials and must not be rejected with 401. */
    @Override
    public int priority() {
        return 4000;
    }

    @Override
    public String schemaResource() {
        return "schemas/plugins/cors.json";
    }

    @Override
    public Plugin create(JsonNode config, PluginContext context) throws PluginConfigException {
        List<ConfigError> errors = new ArrayList<>();
        Configs.known(config, "", Set.of("allowOrigins", "allowMethods", "allowHeaders", "exposeHeaders",
                "allowCredentials", "maxAgeSeconds"), errors);
        List<String> origins = Configs.strings(config, "", "allowOrigins", errors);
        List<String> methods = Configs.strings(config, "", "allowMethods", errors);
        List<String> headers = Configs.headerNames(config, "", "allowHeaders", errors);
        List<String> expose = Configs.headerNames(config, "", "exposeHeaders", errors);
        boolean credentials = Configs.bool(config, "", "allowCredentials", false, errors);
        int maxAge = Configs.integer(config, "", "maxAgeSeconds", 600, 0, 86_400, errors);
        boolean any = false;
        List<String> exact = new ArrayList<>();
        List<String[]> wildcard = new ArrayList<>();
        if (origins.isEmpty()) {
            errors.add(new ConfigError("allowOrigins", "is required (at least one origin, or \"*\")"));
        }
        for (int i = 0; i < origins.size(); i++) {
            String o = origins.get(i);
            String p = "allowOrigins[" + i + "]";
            if (o.equals("*")) {
                any = true;
            } else if (!o.matches("^https?://(\\*\\.)?[a-z0-9.-]+(:[0-9]{1,5})?$")) {
                errors.add(new ConfigError(p, "must be \"*\" or scheme://host[:port] in lower case, "
                        + "optionally with a leading *. subdomain wildcard"));
            } else if (o.contains("://*.")) {
                int at = o.indexOf("://*.");
                // [scheme://, .rest] e.g. ["https://", ".example.com"] (suffix includes the dot)
                wildcard.add(new String[] {o.substring(0, at + 3), o.substring(at + 4)});
            } else {
                exact.add(o);
            }
        }
        if (any && credentials) {
            errors.add(new ConfigError("allowCredentials", "cannot be true when allowOrigins contains \"*\""));
        }
        List<String> allowMethods = new ArrayList<>();
        for (int i = 0; i < (methods.isEmpty() ? 0 : methods.size()); i++) {
            String m = methods.get(i).toUpperCase(Locale.ROOT);
            if (!Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").contains(m)) {
                errors.add(new ConfigError("allowMethods[" + i + "]", "unknown method"));
            } else {
                allowMethods.add(m);
            }
        }
        if (allowMethods.isEmpty()) {
            allowMethods = List.of("GET", "HEAD", "POST");
        }
        if (!errors.isEmpty()) {
            throw new PluginConfigException(errors);
        }
        return new Cors(any, Set.copyOf(exact), wildcard.toArray(String[][]::new), Set.copyOf(allowMethods),
                String.join(", ", allowMethods), Set.copyOf(headers), String.join(", ", headers),
                String.join(", ", expose), credentials, Integer.toString(maxAge));
    }

    /** Compiled CORS policy. All header values are precomputed. */
    record Cors(boolean anyOrigin, Set<String> origins, String[][] wildcards, Set<String> methods,
            String methodsValue, Set<String> headers, String headersValue, String exposeValue,
            boolean credentials, String maxAge) implements RequestPlugin, ResponseHeadersPlugin {

        boolean allowed(String origin) {
            if (anyOrigin || origins.contains(origin)) {
                return true;
            }
            for (String[] w : wildcards) {
                if (origin.startsWith(w[0]) && origin.length() > w[0].length() + w[1].length()
                        && endsWithHost(origin, w[0].length(), w[1])) {
                    return true;
                }
            }
            return false;
        }

        /** The part after the scheme ends with {@code .suffix} (port included in the suffix if configured). */
        private static boolean endsWithHost(String origin, int hostStart, String suffix) {
            if (!origin.endsWith(suffix)) {
                return false;
            }
            for (int i = hostStart; i < origin.length() - suffix.length(); i++) {
                char c = origin.charAt(i);
                if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.')) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public Outcome onRequest(Exchange exchange) {
            var req = exchange.request();
            String origin = req.getHeader("origin");
            if (origin == null || req.method() != HttpMethod.OPTIONS) {
                return Outcome.CONTINUE;
            }
            String method = req.getHeader("access-control-request-method");
            if (method == null) {
                // A plain OPTIONS request with an Origin: not a preflight; proxy it.
                return Outcome.CONTINUE;
            }
            if (!allowed(origin) || !methods.contains(method.trim())) {
                return exchange.respond(403, "Forbidden");
            }
            String requested = req.getHeader("access-control-request-headers");
            if (requested != null && !requested.isBlank()) {
                for (String h : requested.split(",")) {
                    String name = h.trim().toLowerCase(Locale.ROOT);
                    if (!name.isEmpty() && !headers.contains(name) && !SAFELISTED.contains(name)) {
                        return exchange.respond(403, "Forbidden");
                    }
                }
            }
            MultiMap out = MultiMap.caseInsensitiveMultiMap();
            out.set("access-control-allow-origin", anyOrigin && !credentials ? "*" : origin);
            out.set("access-control-allow-methods", methodsValue);
            if (!headersValue.isEmpty()) {
                out.set("access-control-allow-headers", headersValue);
            }
            if (credentials) {
                out.set("access-control-allow-credentials", "true");
            }
            out.set("access-control-max-age", maxAge);
            out.set("vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
            return exchange.respondWith(204, out, null);
        }

        @Override
        public void onResponseHeaders(Exchange exchange, int status, MultiMap headers) {
            if (!anyOrigin) {
                // The response differs by Origin: shared caches must key on it.
                headers.add("vary", "Origin");
            }
            String origin = exchange.request().getHeader("origin");
            if (origin == null || !allowed(origin)) {
                return;
            }
            // The upstream must not widen the policy: the gateway's answer replaces any upstream CORS headers.
            headers.set("access-control-allow-origin", anyOrigin && !credentials ? "*" : origin);
            if (credentials) {
                headers.set("access-control-allow-credentials", "true");
            } else {
                headers.remove("access-control-allow-credentials");
            }
            if (!exposeValue.isEmpty()) {
                headers.set("access-control-expose-headers", exposeValue);
            }
        }
    }
}
