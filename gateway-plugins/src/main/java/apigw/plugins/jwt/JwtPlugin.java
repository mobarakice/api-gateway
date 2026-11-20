package apigw.plugins.jwt;

import apigw.core.auth.Attributes;
import apigw.core.auth.CompiledConsumer;
import apigw.core.auth.Principal;
import apigw.core.plugin.Exchange;
import apigw.core.plugin.Outcome;
import apigw.core.plugin.RequestPlugin;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authenticates {@code Authorization: Bearer <JWS>} tokens. A token naming an unknown {@code kid} of a
 * remote key set waits (asynchronously, on its event loop) for one rate-limited refresh before it is
 * rejected, so key rotation at the IdP does not cause a burst of 401s.
 */
final class JwtPlugin implements RequestPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(JwtPlugin.class);
    private static final String CHALLENGE = "Bearer realm=\"gateway\"";
    private static final String INVALID = "Bearer realm=\"gateway\", error=\"invalid_token\"";

    /** Issuer rules plus how to build the principal from its claims. */
    record IssuerRuntime(JwtVerifier.Issuer verifierIssuer, OidcKeySources.Source remote, String tenantClaim,
            String rolesClaim, String consumerClaim, boolean requireConsumer) {
    }

    private final java.util.Map<String, IssuerRuntime> issuers;
    private final JwtVerifier verifier;
    private final String header;
    private final boolean optional;
    private final boolean forwardToken;
    private final Clock clock;

    JwtPlugin(List<IssuerRuntime> issuers, JwtVerifier verifier, String header, boolean optional,
            boolean forwardToken, Clock clock) {
        java.util.Map<String, IssuerRuntime> m = new java.util.HashMap<>();
        issuers.forEach(i -> m.put(i.verifierIssuer().issuer(), i));
        this.issuers = java.util.Map.copyOf(m);
        this.verifier = verifier;
        this.header = header;
        this.optional = optional;
        this.forwardToken = forwardToken;
        this.clock = clock;
    }

    @Override
    public Outcome onRequest(Exchange ex) {
        if (ex.principal() != null) {
            return Outcome.CONTINUE;
        }
        String value = ex.request().headers().get(header);
        String token = bearer(value);
        if (token == null) {
            if (value == null && optional) {
                return Outcome.CONTINUE;
            }
            return ex.respond(401, "Unauthorized", "WWW-Authenticate", value == null ? CHALLENGE : INVALID);
        }
        JwtVerifier.Result r = verifier.verify(token, clock.instant(), (jti, iss, now) ->
                ex.snapshot().security().isRevoked(jti, iss, now));
        if (r instanceof JwtVerifier.UnknownKey u) {
            IssuerRuntime rt = issuers.get(u.issuer().issuer());
            if (rt.remote() == null) {
                return reject(ex, JwtVerifier.Reason.UNKNOWN_KEY);
            }
            Context loop = Vertx.currentContext();
            rt.remote().refreshForUnknownKey().onComplete(ar -> loop.runOnContext(v -> {
                JwtVerifier.Result again = verifier.verify(token, clock.instant(), (jti, iss, now) ->
                        ex.snapshot().security().isRevoked(jti, iss, now));
                Outcome o = again instanceof JwtVerifier.Valid valid ? accept(ex, valid)
                        : reject(ex, again instanceof JwtVerifier.Invalid inv ? inv.reason()
                                : JwtVerifier.Reason.UNKNOWN_KEY);
                if (o == Outcome.CONTINUE) {
                    ex.resume();
                }
            }));
            return Outcome.PENDING;
        }
        if (r instanceof JwtVerifier.Valid v) {
            return accept(ex, v);
        }
        return reject(ex, ((JwtVerifier.Invalid) r).reason());
    }

    private Outcome accept(Exchange ex, JwtVerifier.Valid v) {
        IssuerRuntime rt = issuers.get(v.issuer().issuer());
        JsonNode claims = v.claims();
        CompiledConsumer consumer = null;
        if (!rt.consumerClaim().isEmpty()) {
            JsonNode c = claim(claims, rt.consumerClaim());
            consumer = c != null && c.isTextual() ? ex.snapshot().security().consumers().get(c.textValue()) : null;
            if (consumer == null && rt.requireConsumer()) {
                return reject(ex, JwtVerifier.Reason.UNKNOWN_CONSUMER);
            }
        }
        String tenant = null;
        if (!rt.tenantClaim().isEmpty()) {
            JsonNode t = claim(claims, rt.tenantClaim());
            tenant = t != null && t.isTextual() ? t.textValue() : null;
        }
        if (consumer != null) {
            if (tenant != null && !tenant.equals(consumer.tenantId())) {
                // The token's tenant and its consumer's tenant disagree: never guess which one is right.
                return reject(ex, JwtVerifier.Reason.TENANT_MISMATCH);
            }
            tenant = consumer.tenantId();
        }
        Set<String> roles = new HashSet<>(consumer == null ? Set.of() : consumer.roles());
        if (!rt.rolesClaim().isEmpty()) {
            JsonNode rc = claim(claims, rt.rolesClaim());
            if (rc != null && rc.isArray()) {
                rc.forEach(x -> {
                    if (x.isTextual()) {
                        roles.add(x.textValue());
                    }
                });
            } else if (rc != null && rc.isTextual()) {
                for (String s : rc.textValue().split(" ")) {
                    if (!s.isEmpty()) {
                        roles.add(s);
                    }
                }
            }
        }
        JsonNode sub = claims.get("sub");
        Attributes attrs = name -> values(claim(claims, name));
        Principal p = new Principal(sub != null && sub.isTextual() ? sub.textValue() : null, consumer, tenant, roles,
                attrs, "jwt", v.issuer().issuer());
        Outcome o = ex.authenticate(p);
        if (o == Outcome.CONTINUE && !forwardToken) {
            ex.upstreamHeaders().remove(header);
        }
        return o;
    }

    private Outcome reject(Exchange ex, JwtVerifier.Reason reason) {
        // Debug only: rejections are attacker-controlled volume. Never log the token.
        LOG.debug("jwt rejected route={} requestId={} reason={}", ex.route().id(), ex.requestId(), reason);
        return ex.respond(401, "Unauthorized", "WWW-Authenticate", INVALID);
    }

    private static String bearer(String value) {
        if (value == null || value.length() < 8 || !value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String t = value.substring(7).trim();
        return t.isEmpty() ? null : t;
    }

    /** A claim by exact name, else by dotted path ({@code realm_access.roles}). */
    static JsonNode claim(JsonNode claims, String name) {
        JsonNode direct = claims.get(name);
        if (direct != null || name.indexOf('.') < 0) {
            return direct;
        }
        JsonNode n = claims;
        for (String part : name.split("\\.")) {
            n = n == null ? null : n.get(part);
        }
        return n;
    }

    private static List<String> values(JsonNode n) {
        if (n == null || n.isNull() || n.isObject()) {
            return List.of();
        }
        if (n.isArray()) {
            List<String> out = new ArrayList<>(n.size());
            n.forEach(x -> {
                if (x.isValueNode()) {
                    out.add(x.asText());
                }
            });
            return out;
        }
        return List.of(n.asText());
    }
}
