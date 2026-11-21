package apigw.controlplane.auth;

import static org.assertj.core.api.Assertions.assertThat;

import apigw.controlplane.Json;
import apigw.plugins.jwt.JwtAlgorithm;
import apigw.plugins.jwt.Jwks;
import apigw.plugins.jwt.KeySet;
import apigw.plugins.testing.TestJwt;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AdminAuthTest {

    private static final KeyPair KEY = TestJwt.ec256();

    private static AdminAuth auth(String prefix) {
        KeySet keys = Jwks.parse(Json.parse(TestJwt.jwks(TestJwt.jwk(KEY, "e", "ES256"))
                .getBytes(StandardCharsets.UTF_8))).keys();
        return new AdminAuth(new AdminAuth.Settings("https://idp", Set.of("cp"), EnumSet.of(JwtAlgorithm.ES256),
                "groups", prefix, 30), () -> keys, Clock.systemUTC());
    }

    private static String token(String groups) {
        long now = Instant.now().getEpochSecond();
        return TestJwt.sign(TestJwt.header("ES256", "e"), "{\"iss\":\"https://idp\",\"sub\":\"u1\",\"aud\":\"cp\","
                + "\"exp\":" + (now + 60) + ",\"groups\":" + groups + "}", "ES256", KEY.getPrivate());
    }

    private static Actor actor(AdminAuth a, String token) throws Exception {
        return a.authenticate("Bearer " + token, "10.1.1.1", "r").toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    void highestPrefixedRoleWinsAndOthersAreIgnored() throws Exception {
        AdminAuth a = auth("gw-");
        Actor act = actor(a, token("[\"gw-viewer\",\"gw-publisher\",\"admin\",\"gw-root\"]"));
        assertThat(act.role()).isEqualTo(Role.PUBLISHER);
        assertThat(act.may(Role.EDITOR)).isTrue();
        assertThat(act.may(Role.ADMIN)).isFalse();
        assertThat(actor(a, token("\"gw-editor openid\"")).role()).isEqualTo(Role.EDITOR);
        assertThat(actor(a, token("[\"viewer\"]")).role()).isNull();
    }

    @Test
    void theSchemeIsCaseInsensitiveAndOtherSchemesFail() {
        AdminAuth a = auth("");
        String t = token("[\"viewer\"]");
        assertThat(a.authenticate("bearer " + t, "ip", "r").succeeded()).isTrue();
        assertThat(a.authenticate("Basic " + t, "ip", "r").failed()).isTrue();
        assertThat(a.authenticate(null, "ip", "r").failed()).isTrue();
    }
}
