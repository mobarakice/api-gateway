package apigw.plugins.keyauth;

import apigw.core.auth.Principal;
import apigw.core.auth.SecurityIndex;
import apigw.core.plugin.Exchange;
import apigw.core.plugin.Outcome;
import apigw.core.plugin.Phase;
import apigw.core.plugin.Plugin;
import apigw.core.plugin.PluginConfigException;
import apigw.core.plugin.PluginContext;
import apigw.core.plugin.PluginFactory;
import apigw.core.plugin.RequestPlugin;
import apigw.model.ApiKeyHash;
import apigw.model.ConfigError;
import apigw.plugins.support.Configs;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for {@code key-auth}. The presented key is {@code <keyId>.<secret>}: the public key ID selects
 * the credential in the snapshot's index (one hash lookup, no scan), then the secret is checked
 * against the stored salted SHA-256 in constant time. An unknown key ID and a wrong secret produce the
 * same 401. Config: {@code schemas/plugins/key-auth.json}.
 */
public final class KeyAuthPluginFactory implements PluginFactory {

    public static final String NAME = "key-auth";

    @Override
    public String name() {
        return NAME;
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
        return 2050;
    }

    @Override
    public boolean authenticates() {
        return true;
    }

    @Override
    public String schemaResource() {
        return "schemas/plugins/key-auth.json";
    }

    @Override
    public Plugin create(JsonNode config, PluginContext context) throws PluginConfigException {
        List<ConfigError> errors = new ArrayList<>();
        Configs.known(config, "", Set.of("header", "optional", "hideCredentials"), errors);
        String header = Configs.string(config, "", "header", "x-api-key", errors).toLowerCase(Locale.ROOT);
        if (!header.matches("[!#$%&'*+.^_`|~0-9a-z-]{1,128}")) {
            errors.add(new ConfigError("header", "not a valid header name"));
        }
        boolean optional = Configs.bool(config, "", "optional", false, errors);
        boolean hide = Configs.bool(config, "", "hideCredentials", true, errors);
        if (!errors.isEmpty()) {
            throw new PluginConfigException(errors);
        }
        return new KeyAuth(header, optional, hide);
    }

    /** The compiled instance. */
    record KeyAuth(String header, boolean optional, boolean hideCredentials) implements RequestPlugin {

        private static final Logger LOG = LoggerFactory.getLogger(KeyAuth.class);
        private static final String CHALLENGE = "ApiKey realm=\"gateway\"";

        @Override
        public Outcome onRequest(Exchange ex) {
            if (ex.principal() != null) {
                return Outcome.CONTINUE;
            }
            String presented = ex.request().headers().get(header);
            if (presented == null) {
                return optional ? Outcome.CONTINUE : ex.respond(401, "Unauthorized", "WWW-Authenticate", CHALLENGE);
            }
            int dot = presented.indexOf('.');
            SecurityIndex.ApiKey key = dot > 0 ? ex.snapshot().security().apiKeys().get(presented.substring(0, dot))
                    : null;
            String secret = dot > 0 ? presented.substring(dot + 1) : "";
            boolean ok = key != null && key.consumer() != null && secret.length() >= ApiKeyHash.MIN_SECRET_LENGTH
                    && secret.length() <= 512
                    && ApiKeyHash.matches(new ApiKeyHash.Parsed(key.salt(), key.digest()), secret);
            if (!ok) {
                LOG.debug("key-auth rejected route={} requestId={}", ex.route().id(), ex.requestId());
                return ex.respond(401, "Unauthorized", "WWW-Authenticate", CHALLENGE);
            }
            if (hideCredentials) {
                ex.upstreamHeaders().remove(header);
            }
            return ex.authenticate(Principal.of(key.consumer(), "key-auth", key.credentialId()));
        }
    }
}
