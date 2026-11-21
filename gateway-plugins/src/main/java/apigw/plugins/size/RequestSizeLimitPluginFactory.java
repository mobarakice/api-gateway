package apigw.plugins.size;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code request-size-limit}: a per-route request body cap, lower than the global
 * {@code settings.maxRequestBodyBytes}. A declared {@code Content-Length} over the cap gets 413 at
 * once; a chunked body is counted while it streams and cut off with 413 when it passes the cap.
 * Config: <pre>{"maxBytes": 65536}</pre>
 */
public final class RequestSizeLimitPluginFactory implements PluginFactory {

    @Override
    public String name() {
        return "request-size-limit";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public Set<Phase> phases() {
        return Set.of(Phase.ACCESS);
    }

    @Override
    public int priority() {
        return 2900;
    }

    @Override
    public String schemaResource() {
        return "schemas/plugins/request-size-limit.json";
    }

    @Override
    public Plugin create(JsonNode config, PluginContext context) throws PluginConfigException {
        List<ConfigError> errors = new ArrayList<>();
        Configs.known(config, "", Set.of("maxBytes"), errors);
        int max = Configs.integer(config, "", "maxBytes", -1, 0, Integer.MAX_VALUE, errors);
        if (max < 0 && errors.isEmpty()) {
            errors.add(new ConfigError("maxBytes", "is required"));
        }
        if (!errors.isEmpty()) {
            throw new PluginConfigException(errors);
        }
        return new SizeLimit(max);
    }

    record SizeLimit(long maxBytes) implements RequestPlugin {

        @Override
        public Outcome onRequest(Exchange exchange) {
            String cl = exchange.request().getHeader("content-length");
            if (cl != null) {
                // The request validator already rejected malformed or conflicting lengths.
                long declared = Long.parseLong(cl.trim());
                if (declared > maxBytes) {
                    return exchange.respond(413, "Content Too Large");
                }
            }
            exchange.limitRequestBody(maxBytes);
            return Outcome.CONTINUE;
        }
    }
}
