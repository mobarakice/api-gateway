package apigw.controlplane.auth;

import apigw.controlplane.api.ApiException;
import apigw.plugins.jwt.JwtAlgorithm;
import apigw.plugins.jwt.JwtVerifier;
import apigw.plugins.jwt.KeySet;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bearer-token authentication for the Admin API (OIDC access tokens, ADR 0020). Verification is the
 * gateway's own {@link JwtVerifier}: pinned algorithms, exact issuer, audience, {@code exp} required,
 * clock skew. The role comes from a claim ({@code roles} by default, an array or a space-separated
 * string); the highest recognised role wins. A valid token with no recognised role is a 403.
 */
public final class AdminAuth {

    private static final Logger LOG = LoggerFactory.getLogger(AdminAuth.class);
    private static final JwtVerifier.Revocations NONE = (jti, iss, now) -> false;
    private static final int MAX_TOKEN_BYTES = 16 * 1024;

    /** Where verification keys come from; {@link #refresh()} is called once for an unknown {@code kid}. */
    public interface Keys extends Supplier<KeySet> {
        default Future<Boolean> refresh() {
            return Future.succeededFuture(false);
        }
    }

    /**
     * @param rolePrefix prepended to role names in the claim, e.g. {@code gw-} for {@code gw-editor}
     */
    public record Settings(String issuer, Set<String> audiences, Set<JwtAlgorithm> algorithms, String rolesClaim,
            String rolePrefix, long skewSeconds) {
        public Settings {
            audiences = Set.copyOf(audiences);
            algorithms = Set.copyOf(algorithms);
            if (issuer == null || issuer.isBlank() || audiences.isEmpty() || algorithms.isEmpty()) {
                throw new IllegalArgumentException("admin OIDC issuer, audience and algorithms are required");
            }
        }
    }

    private final Settings settings;
    private final Keys keys;
    private final JwtVerifier verifier;
    private final Clock clock;

    public AdminAuth(Settings settings, Keys keys, Clock clock) {
        this.settings = settings;
        this.keys = keys;
        this.clock = clock;
        this.verifier = new JwtVerifier(List.of(new JwtVerifier.Issuer(settings.issuer(), settings.audiences(),
                EnumSet.copyOf(settings.algorithms()), keys, 0)), settings.skewSeconds(), MAX_TOKEN_BYTES);
    }

    /** The caller, or a failed future with a 401 (bad or missing token) or 403 (no admin role). */
    public Future<Actor> authenticate(String authorization, String sourceIp, String requestId) {
        String token = bearer(authorization);
        if (token == null) {
            return Future.failedFuture(unauthorized());
        }
        JwtVerifier.Result r = verifier.verify(token, clock.instant(), NONE);
        if (r instanceof JwtVerifier.UnknownKey) {
            return keys.refresh().compose(ignored -> finish(verifier.verify(token, clock.instant(), NONE), sourceIp,
                    requestId));
        }
        return finish(r, sourceIp, requestId);
    }

    private Future<Actor> finish(JwtVerifier.Result r, String sourceIp, String requestId) {
        if (!(r instanceof JwtVerifier.Valid v)) {
            String reason = r instanceof JwtVerifier.Invalid i ? i.reason().name() : "UNKNOWN_KEY";
            LOG.info("admin token rejected reason={} requestId={}", reason, requestId);
            return Future.failedFuture(unauthorized());
        }
        String subject = v.claims().path("sub").asText("");
        if (subject.isEmpty()) {
            LOG.info("admin token rejected reason=MISSING_SUB requestId={}", requestId);
            return Future.failedFuture(unauthorized());
        }
        Set<Role> roles = roles(v.claims().get(settings.rolesClaim()));
        Role top = roles.stream().max(Role::compareTo).orElse(null);
        return Future.succeededFuture(new Actor(subject, top, roles, sourceIp, requestId));
    }

    private Set<Role> roles(JsonNode claim) {
        Set<Role> out = EnumSet.noneOf(Role.class);
        if (claim == null) {
            return out;
        }
        if (claim.isArray()) {
            claim.forEach(c -> role(c.asText(), out));
        } else if (claim.isTextual()) {
            for (String s : claim.asText().split(" ")) {
                role(s, out);
            }
        }
        return out;
    }

    private void role(String value, Set<Role> out) {
        if (!value.startsWith(settings.rolePrefix())) {
            return;
        }
        String name = value.substring(settings.rolePrefix().length()).toUpperCase(Locale.ROOT);
        for (Role r : Role.values()) {
            if (r.name().equals(name)) {
                out.add(r);
            }
        }
    }

    private static String bearer(String header) {
        if (header == null || header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String t = header.substring(7).strip();
        return t.isEmpty() ? null : t;
    }

    private static ApiException unauthorized() {
        return new ApiException(401, "Unauthorized", "a valid bearer token is required");
    }
}
