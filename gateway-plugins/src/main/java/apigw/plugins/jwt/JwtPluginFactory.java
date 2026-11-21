package apigw.plugins.jwt;

import apigw.core.plugin.Phase;
import apigw.core.plugin.Plugin;
import apigw.core.plugin.PluginConfigException;
import apigw.core.plugin.PluginContext;
import apigw.core.plugin.PluginFactory;
import apigw.model.ConfigError;
import apigw.plugins.support.Configs;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Factory for the {@code jwt} plugin. Config: {@code schemas/plugins/jwt.json}. */
public final class JwtPluginFactory implements PluginFactory {

    public static final String NAME = "jwt";
    static final int PRIORITY = 2100;

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
        return PRIORITY;
    }

    @Override
    public boolean authenticates() {
        return true;
    }

    @Override
    public String schemaResource() {
        return "schemas/plugins/jwt.json";
    }

    @Override
    public Plugin create(JsonNode config, PluginContext context) throws PluginConfigException {
        List<ConfigError> errors = new ArrayList<>();
        Configs.known(config, "", Set.of("issuers", "header", "clockSkewSeconds", "maxTokenBytes", "optional",
                "forwardToken"), errors);
        String header = Configs.string(config, "", "header", "authorization", errors).toLowerCase(Locale.ROOT);
        int skew = Configs.integer(config, "", "clockSkewSeconds", 60, 0, 300, errors);
        int maxBytes = Configs.integer(config, "", "maxTokenBytes", 8192, 512, 65_536, errors);
        boolean optional = Configs.bool(config, "", "optional", false, errors);
        boolean forward = Configs.bool(config, "", "forwardToken", true, errors);
        JsonNode list = config.get("issuers");
        List<JwtPlugin.IssuerRuntime> issuers = new ArrayList<>();
        if (list == null || !list.isArray() || list.isEmpty()) {
            errors.add(new ConfigError("issuers", "at least one issuer is required"));
        } else {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < list.size(); i++) {
                JwtPlugin.IssuerRuntime r = issuer(list.get(i), "issuers[" + i + "]", context, errors);
                if (r != null && !seen.add(r.verifierIssuer().issuer())) {
                    errors.add(new ConfigError("issuers[" + i + "].issuer", "duplicate issuer"));
                } else if (r != null) {
                    issuers.add(r);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new PluginConfigException(errors);
        }
        return new JwtPlugin(issuers, new JwtVerifier(issuers.stream().map(JwtPlugin.IssuerRuntime::verifierIssuer)
                .toList(), skew, maxBytes), header, optional, forward, context.clock());
    }

    private static JwtPlugin.IssuerRuntime issuer(JsonNode n, String p, PluginContext ctx, List<ConfigError> errors) {
        if (n == null || !n.isObject()) {
            errors.add(new ConfigError(p, "must be an object"));
            return null;
        }
        Configs.known(n, p, Set.of("issuer", "audiences", "algorithms", "discovery", "jwksUri", "jwks", "secretFile",
                "caFile", "refreshSeconds", "minRefreshSeconds", "maxAgeSeconds", "tenantClaim", "rolesClaim",
                "consumerClaim", "requireConsumer"), errors);
        String issuer = Configs.string(n, p, "issuer", null, errors);
        List<String> audiences = Configs.strings(n, p, "audiences", errors);
        if (audiences.isEmpty()) {
            errors.add(new ConfigError(p + ".audiences", "at least one audience is required"));
        }
        Set<JwtAlgorithm> algs = EnumSet.noneOf(JwtAlgorithm.class);
        for (String a : Configs.strings(n, p, "algorithms", errors)) {
            Optional<JwtAlgorithm> alg = JwtAlgorithm.fromJose(a);
            if (alg.isEmpty()) {
                errors.add(new ConfigError(p + ".algorithms", "unsupported algorithm '" + a + "'"
                        + ("none".equalsIgnoreCase(a) ? " (unsigned tokens are never accepted)" : "")));
            } else {
                algs.add(alg.get());
            }
        }
        if (algs.isEmpty()) {
            errors.add(new ConfigError(p + ".algorithms", "pin at least one algorithm"));
        }
        boolean hmac = algs.stream().anyMatch(a -> a.family() == JwtAlgorithm.Family.HMAC);
        if (hmac && algs.stream().anyMatch(a -> a.family() != JwtAlgorithm.Family.HMAC)) {
            errors.add(new ConfigError(p + ".algorithms", "do not mix HMAC (HS*) and public-key algorithms for one "
                    + "issuer"));
        }
        boolean discovery = Configs.bool(n, p, "discovery", false, errors);
        String jwksUri = Configs.string(n, p, "jwksUri", "", errors);
        JsonNode jwks = n.get("jwks");
        String secretFile = Configs.string(n, p, "secretFile", "", errors);
        int sources = (discovery ? 1 : 0) + (jwksUri.isEmpty() ? 0 : 1) + (jwks == null ? 0 : 1)
                + (secretFile.isEmpty() ? 0 : 1);
        if (sources != 1) {
            errors.add(new ConfigError(p, "set exactly one key source: discovery, jwksUri, jwks or secretFile"));
        }
        if (hmac != !secretFile.isEmpty() && sources == 1) {
            errors.add(new ConfigError(p + ".secretFile", "HS* algorithms need secretFile, and secretFile is only for "
                    + "HS*"));
        }
        String caFile = Configs.string(n, p, "caFile", "", errors);
        int refresh = Configs.integer(n, p, "refreshSeconds", 300, 30, 86_400, errors);
        int minRefresh = Configs.integer(n, p, "minRefreshSeconds", 30, 1, 3_600, errors);
        long maxAge = Configs.integer(n, p, "maxAgeSeconds", 0, 0, 31_536_000, errors);
        String tenantClaim = Configs.string(n, p, "tenantClaim", "", errors);
        String rolesClaim = Configs.string(n, p, "rolesClaim", "", errors);
        String consumerClaim = Configs.string(n, p, "consumerClaim", "", errors);
        boolean requireConsumer = Configs.bool(n, p, "requireConsumer", false, errors);
        if (requireConsumer && consumerClaim.isEmpty()) {
            errors.add(new ConfigError(p + ".requireConsumer", "needs consumerClaim"));
        }
        if (issuer == null || issuer.isEmpty()) {
            return null;
        }
        if (discovery && !issuer.startsWith("https://")) {
            errors.add(new ConfigError(p + ".issuer", "discovery requires an https issuer"));
        }
        OidcKeySources.Source remote = null;
        java.util.function.Supplier<KeySet> keys = () -> KeySet.EMPTY;
        if (jwks != null) {
            Jwks.Parsed parsed = Jwks.parse(jwks);
            parsed.problems().forEach(pr -> errors.add(new ConfigError(p + ".jwks." + pr.replaceFirst(":.*", ""),
                    pr.replaceFirst("^[^:]*: ", ""))));
            if (parsed.keys().all().isEmpty()) {
                errors.add(new ConfigError(p + ".jwks", "no usable signing keys"));
            }
            KeySet fixed = parsed.keys();
            keys = () -> fixed;
        } else if (!secretFile.isEmpty()) {
            byte[] secret;
            if (ctx.validationOnly()) {
                // The file is on the gateways, not here: check the path only.
                if (!Path.of(secretFile).isAbsolute()) {
                    errors.add(new ConfigError(p + ".secretFile", "must be an absolute path"));
                }
                secret = null;
            } else {
                secret = readSecret(Path.of(secretFile), p + ".secretFile", errors);
            }
            int min = algs.stream().mapToInt(JwtAlgorithm::size).max().orElse(32);
            if (secret != null && secret.length < min) {
                errors.add(new ConfigError(p + ".secretFile", "secret must be at least " + min
                        + " bytes for the pinned algorithms"));
            }
            KeySet fixed = secret == null ? KeySet.EMPTY : Jwks.hmac(secret);
            keys = () -> fixed;
        } else if (discovery || !jwksUri.isEmpty()) {
            URI uri = null;
            if (!jwksUri.isEmpty()) {
                try {
                    uri = URI.create(jwksUri);
                    if (!"https".equals(uri.getScheme()) || uri.getHost() == null) {
                        errors.add(new ConfigError(p + ".jwksUri", "must be an https URL"));
                    }
                } catch (IllegalArgumentException e) {
                    errors.add(new ConfigError(p + ".jwksUri", "not a URL"));
                }
            }
            Optional<OidcKeySources> service = ctx.services().find(OidcKeySources.class);
            if (service.isEmpty()) {
                errors.add(new ConfigError(p, "remote key sets are not available in this process"));
            } else if (errors.isEmpty()) {
                remote = service.get().source(new OidcKeySources.Spec(discovery ? issuer : null, uri,
                        caFile.isEmpty() ? Optional.empty() : Optional.of(Path.of(caFile)),
                        Duration.ofSeconds(refresh), Duration.ofSeconds(minRefresh)));
                ctx.use(remote);
                OidcKeySources.Source src = remote;
                keys = src::keys;
            }
        }
        if (!caFile.isEmpty() && !discovery && jwksUri.isEmpty()) {
            errors.add(new ConfigError(p + ".caFile", "only used with discovery or jwksUri"));
        }
        return new JwtPlugin.IssuerRuntime(new JwtVerifier.Issuer(issuer, Set.copyOf(audiences), algs, keys, maxAge),
                remote, tenantClaim, rolesClaim, consumerClaim, requireConsumer);
    }

    private static byte[] readSecret(Path file, String p, List<ConfigError> errors) {
        try {
            byte[] raw = Files.readAllBytes(file);
            int end = raw.length;
            while (end > 0 && (raw[end - 1] == '\n' || raw[end - 1] == '\r')) {
                end--;
            }
            return java.util.Arrays.copyOf(raw, end);
        } catch (IOException e) {
            // Never echo file content; the path is operator-supplied config.
            errors.add(new ConfigError(p, "cannot read the secret file"));
            return null;
        }
    }
}
