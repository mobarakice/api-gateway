package apigw.plugins.ip;

import apigw.core.plugin.Exchange;
import apigw.core.plugin.Outcome;
import apigw.core.plugin.Phase;
import apigw.core.plugin.Plugin;
import apigw.core.plugin.PluginConfigException;
import apigw.core.plugin.PluginContext;
import apigw.core.plugin.PluginFactory;
import apigw.core.plugin.RequestPlugin;
import apigw.model.Cidr;
import apigw.model.ConfigError;
import apigw.plugins.support.Configs;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code ip-restriction}: allow/deny by client IP. The client IP is the one the pipeline derived from
 * {@code settings.trustedProxies} (rightmost untrusted {@code X-Forwarded-For} hop), never a header
 * the client controls directly. Deny wins; a non-empty allow list denies everything else. Config:
 * <pre>{"allow": ["10.0.0.0/8"], "deny": ["10.6.6.0/24"], "status": 403}</pre>
 */
public final class IpRestrictionPluginFactory implements PluginFactory {

    @Override
    public String name() {
        return "ip-restriction";
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
        return 3000;
    }

    @Override
    public String schemaResource() {
        return "schemas/plugins/ip-restriction.json";
    }

    @Override
    public Plugin create(JsonNode config, PluginContext context) throws PluginConfigException {
        List<ConfigError> errors = new ArrayList<>();
        Configs.known(config, "", Set.of("allow", "deny", "status"), errors);
        Cidr[] allow = cidrs(config, "allow", errors);
        Cidr[] deny = cidrs(config, "deny", errors);
        int status = Configs.integer(config, "", "status", 403, 400, 499, errors);
        if (errors.isEmpty() && allow.length == 0 && deny.length == 0) {
            errors.add(new ConfigError("", "configure allow and/or deny"));
        }
        if (!errors.isEmpty()) {
            throw new PluginConfigException(errors);
        }
        return new IpRestriction(allow, deny, status, status == 403 ? "Forbidden" : "Error");
    }

    private static Cidr[] cidrs(JsonNode config, String field, List<ConfigError> errors) {
        List<String> raw = Configs.strings(config, "", field, errors);
        List<Cidr> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            try {
                String v = raw.get(i);
                out.add(Cidr.parse(v.indexOf('/') < 0 ? v + (v.indexOf(':') >= 0 ? "/128" : "/32") : v));
            } catch (IllegalArgumentException e) {
                errors.add(new ConfigError(field + "[" + i + "]", "not an IP address or CIDR"));
            }
        }
        if (out.size() > 10_000) {
            errors.add(new ConfigError(field, "at most 10000 entries"));
        }
        return out.toArray(Cidr[]::new);
    }

    /** Compiled rule set; linear scan (lists are short; large lists belong in a firewall). */
    record IpRestriction(Cidr[] allow, Cidr[] deny, int status, String title) implements RequestPlugin {

        @Override
        public Outcome onRequest(Exchange exchange) {
            byte[] ip;
            try {
                ip = Cidr.literal(exchange.clientIp());
            } catch (IllegalArgumentException e) {
                return exchange.respond(status, title);
            }
            for (Cidr c : deny) {
                if (c.contains(ip)) {
                    return exchange.respond(status, title);
                }
            }
            if (allow.length == 0) {
                return Outcome.CONTINUE;
            }
            for (Cidr c : allow) {
                if (c.contains(ip)) {
                    return Outcome.CONTINUE;
                }
            }
            return exchange.respond(status, title);
        }
    }
}
