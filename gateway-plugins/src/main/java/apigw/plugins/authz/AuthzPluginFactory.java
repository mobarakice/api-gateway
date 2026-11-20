package apigw.plugins.authz;

import apigw.core.auth.Principal;
import apigw.core.plugin.Exchange;
import apigw.core.plugin.Outcome;
import apigw.core.plugin.Phase;
import apigw.core.plugin.Plugin;
import apigw.core.plugin.PluginConfigException;
import apigw.core.plugin.PluginContext;
import apigw.core.plugin.PluginFactory;
import apigw.core.plugin.RequestPlugin;
import apigw.core.routing.MethodBits;
import apigw.model.AuthzPolicy;
import apigw.model.ConfigError;
import apigw.plugins.support.Configs;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for {@code authz}: evaluates the referenced {@code policies/authz/*} after authentication.
 * Policies are indexed by route ID at compile time; per request only the route's policies and the
 * route-agnostic ones are evaluated. Any matching deny wins; otherwise any matching allow permits;
 * otherwise 403 (401 when nobody authenticated). Config: {@code schemas/plugins/authz.json}.
 */
public final class AuthzPluginFactory implements PluginFactory {

    public static final String NAME = "authz";

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
        return 1000;
    }

    @Override
    public String schemaResource() {
        return "schemas/plugins/authz.json";
    }

    @Override
    public Plugin create(JsonNode config, PluginContext context) throws PluginConfigException {
        List<ConfigError> errors = new ArrayList<>();
        Configs.known(config, "", Set.of("policies"), errors);
        List<String> ids = Configs.strings(config, "", "policies", errors);
        if (ids.isEmpty()) {
            errors.add(new ConfigError("policies", "reference at least one policy (policies/authz/{id})"));
        }
        List<Rule> any = new ArrayList<>();
        Map<String, List<Rule>> byRoute = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            AuthzPolicy p = context.authzPolicies().get(ids.get(i));
            if (p == null) {
                errors.add(new ConfigError("policies[" + i + "]", "unknown policy '" + ids.get(i) + "'"));
                continue;
            }
            Rule r = new Rule(p.id(), p.tenantId(), p.effect() == AuthzPolicy.Effect.DENY, MethodBits.of(p.methods()),
                    Set.copyOf(p.subjects().roles()), Set.copyOf(p.subjects().consumers()),
                    p.subjects().anyAuthenticated(), p.subjects().anyone(),
                    p.condition() == null ? null : CompiledCondition.of(p.condition()));
            if (p.routes().isEmpty()) {
                any.add(r);
            } else {
                p.routes().forEach(route -> byRoute.computeIfAbsent(route, k -> new ArrayList<>()).add(r));
            }
        }
        if (!errors.isEmpty()) {
            throw new PluginConfigException(errors);
        }
        Map<String, Rule[]> index = new HashMap<>();
        byRoute.forEach((k, v) -> index.put(k, v.toArray(Rule[]::new)));
        return new Authz(Map.copyOf(index), any.toArray(Rule[]::new));
    }

    /** One compiled policy. */
    record Rule(String id, String tenantId, boolean deny, int methods, Set<String> roles, Set<String> consumers,
            boolean anyAuthenticated, boolean anyone, CompiledCondition condition) {

        boolean matches(Exchange ex, Principal p) {
            if (tenantId != null && !tenantId.equals(ex.route().tenantId())) {
                return false;
            }
            if (methods != MethodBits.ANY && (methods & MethodBits.of(ex.request().method().name())) == 0) {
                return false;
            }
            if (!anyone) {
                boolean subject = p != null && (anyAuthenticated
                        || (p.consumer() != null && consumers.contains(p.consumer().id()))
                        || intersects(roles, p.roles()));
                if (!subject) {
                    return false;
                }
            }
            return condition == null || condition.test(ex);
        }

        private static boolean intersects(Set<String> wanted, Set<String> have) {
            for (String r : wanted) {
                if (have.contains(r)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The compiled instance. */
    record Authz(Map<String, Rule[]> byRoute, Rule[] any) implements RequestPlugin {

        private static final Logger LOG = LoggerFactory.getLogger(Authz.class);
        private static final Rule[] NONE = new Rule[0];

        @Override
        public Outcome onRequest(Exchange ex) {
            Principal p = ex.principal();
            int a = evaluate(byRoute.getOrDefault(ex.route().id(), NONE), ex, p);
            int b = a == DENIED ? DENIED : evaluate(any, ex, p);
            if (b == DENIED) {
                return deny(ex, p);
            }
            if (a == ALLOWED || b == ALLOWED) {
                return Outcome.CONTINUE;
            }
            LOG.debug("authz no matching allow route={} requestId={}", ex.route().id(), ex.requestId());
            return deny(ex, p);
        }

        private static final int NO_MATCH = 0;
        private static final int ALLOWED = 1;
        private static final int DENIED = 2;

        private static int evaluate(Rule[] rules, Exchange ex, Principal p) {
            int result = NO_MATCH;
            for (Rule r : rules) {
                if (r.matches(ex, p)) {
                    if (r.deny()) {
                        LOG.debug("authz denied route={} requestId={} policy={}", ex.route().id(), ex.requestId(),
                                r.id());
                        return DENIED;
                    }
                    result = ALLOWED;
                }
            }
            return result;
        }

        private static Outcome deny(Exchange ex, Principal p) {
            return p == null ? ex.respond(401, "Unauthorized") : ex.respond(403, "Forbidden");
        }
    }
}
