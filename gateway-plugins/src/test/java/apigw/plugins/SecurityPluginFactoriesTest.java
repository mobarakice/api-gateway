package apigw.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import apigw.core.plugin.PluginConfigException;
import apigw.core.plugin.PluginContext;
import apigw.core.plugin.PluginFactory;
import apigw.core.plugin.PluginServices;
import apigw.core.plugin.RequestPlugin;
import apigw.model.AuthzPolicy;
import apigw.model.ConfigError;
import apigw.plugins.authz.AuthzPluginFactory;
import apigw.plugins.jwt.JwtPluginFactory;
import apigw.plugins.keyauth.KeyAuthPluginFactory;
import apigw.plugins.testing.TestJwt;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecurityPluginFactoriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PluginContext CTX = PluginContext.empty();
    private static final String JWKS = TestJwt.jwks(TestJwt.jwk(TestJwt.rsa(), "k", "RS256"));

    private static List<String> errors(PluginFactory f, String config, PluginContext ctx) {
        try {
            f.create(JSON.readTree(config), ctx);
            return List.of();
        } catch (PluginConfigException e) {
            return e.errors().stream().map(ConfigError::toString).toList();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void jwtAcceptsAValidInlineJwksIssuer() throws Exception {
        assertThat(new JwtPluginFactory().create(JSON.readTree("{\"issuers\":[{\"issuer\":\"i\",\"audiences\":[\"a\"],"
                + "\"algorithms\":[\"RS256\"],\"jwks\":" + JWKS + "}]}"), CTX)).isInstanceOf(RequestPlugin.class);
    }

    @Test
    void jwtRefusesUnsafeIssuerConfigs(@TempDir Path dir) throws Exception {
        JwtPluginFactory f = new JwtPluginFactory();
        Path shortSecret = Files.writeString(dir.resolve("s"), "too-short");
        String base = "{\"issuer\":\"https://i\",\"audiences\":[\"a\"],";
        assertThat(errors(f, "{\"issuers\":[" + base + "\"algorithms\":[\"none\"],\"jwks\":" + JWKS + "}]}", CTX))
                .anyMatch(e -> e.contains("unsigned tokens are never accepted"));
        assertThat(errors(f, "{\"issuers\":[" + base + "\"algorithms\":[\"RS256\",\"HS256\"],\"jwks\":" + JWKS + "}]}",
                CTX)).anyMatch(e -> e.contains("do not mix HMAC"));
        assertThat(errors(f, "{\"issuers\":[" + base + "\"algorithms\":[\"RS256\"],\"jwks\":" + JWKS
                + ",\"jwksUri\":\"https://x/jwks\"}]}", CTX)).anyMatch(e -> e.contains("exactly one key source"));
        assertThat(errors(f, "{\"issuers\":[" + base + "\"algorithms\":[\"RS256\"],\"jwksUri\":\"http://x/jwks\"}]}",
                CTX)).anyMatch(e -> e.contains("must be an https URL"));
        assertThat(errors(f, "{\"issuers\":[" + base + "\"algorithms\":[\"HS256\"],\"secretFile\":\"" + shortSecret
                + "\"}]}", CTX)).anyMatch(e -> e.contains("at least 32 bytes"));
        assertThat(errors(f, "{\"issuers\":[" + base + "\"algorithms\":[\"HS256\"],\"secretFile\":\"" + dir.resolve("x")
                + "\"}]}", CTX)).anyMatch(e -> e.contains("cannot read the secret file"));
        assertThat(errors(f, "{\"issuers\":[" + base + "\"algorithms\":[\"RS256\"],"
                + "\"jwks\":{\"keys\":[{\"kty\":\"oct\",\"k\":\"c2VjcmV0\"}]}}]}", CTX))
                .anyMatch(e -> e.contains("no usable signing keys"));
        assertThat(errors(f, "{\"issuers\":[" + base + "\"algorithms\":[\"RS256\"],\"discovery\":true}]}", CTX))
                .anyMatch(e -> e.contains("remote key sets are not available"));
        assertThat(errors(f, "{\"issuers\":[], \"bogus\": 1}", CTX)).anyMatch(e -> e.contains("bogus"))
                .anyMatch(e -> e.contains("at least one issuer"));
    }

    @Test
    void keyAuthAndAuthzConfigs() throws Exception {
        assertThat(errors(new KeyAuthPluginFactory(), "{\"header\":\"bad header\"}", CTX))
                .anyMatch(e -> e.contains("not a valid header name"));
        assertThat(errors(new KeyAuthPluginFactory(), "{}", CTX)).isEmpty();
        PluginContext withPolicy = new PluginContext(Map.of("p", new AuthzPolicy("p", null, AuthzPolicy.Effect.ALLOW,
                List.of(), List.of(), AuthzPolicy.Subjects.ANYONE, null)), PluginServices.NONE, Clock.systemUTC());
        assertThat(errors(new AuthzPluginFactory(), "{\"policies\":[\"p\"]}", withPolicy)).isEmpty();
        assertThat(errors(new AuthzPluginFactory(), "{\"policies\":[\"p\",\"q\"]}", withPolicy))
                .contains("policies[1]: unknown policy 'q'");
        assertThat(errors(new AuthzPluginFactory(), "{\"policies\":[]}", withPolicy))
                .anyMatch(e -> e.contains("at least one policy"));
    }

    @Test
    void authenticatingPluginsRunBeforeAuthz() {
        assertThat(new JwtPluginFactory().authenticates()).isTrue();
        assertThat(new KeyAuthPluginFactory().authenticates()).isTrue();
        assertThat(new AuthzPluginFactory().authenticates()).isFalse();
        assertThat(new JwtPluginFactory().priority()).isGreaterThan(new KeyAuthPluginFactory().priority());
        assertThat(new KeyAuthPluginFactory().priority()).isGreaterThan(new AuthzPluginFactory().priority());
    }
}
