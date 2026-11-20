package apigw.model;

import java.util.List;

/**
 * One RBAC/ABAC rule ({@code policies/authz/{id}}), referenced by the {@code authz} plugin. A request
 * matches when the route, the method, the subject and the condition all match. Any matching
 * {@code deny} wins over every {@code allow}; no match means deny (ADR 0016).
 *
 * @param tenantId when set, the policy only ever matches routes of that tenant
 * @param routes   route IDs; empty = every route the plugin is attached to
 * @param methods  upper-case methods; empty = all
 * @param subjects who it applies to
 * @param condition optional attribute condition; null = always
 */
public record AuthzPolicy(String id, String tenantId, Effect effect, List<String> routes, List<String> methods,
        Subjects subjects, Condition condition) {

    public AuthzPolicy {
        routes = List.copyOf(routes);
        methods = List.copyOf(methods);
    }

    /** Decision when the policy matches. */
    public enum Effect {
        ALLOW, DENY
    }

    /**
     * Subject match: any listed role, any listed consumer, or (if {@code anyAuthenticated}) any
     * authenticated principal. All three empty/false = matches every principal, authenticated or not.
     */
    public record Subjects(List<String> roles, List<String> consumers, boolean anyAuthenticated) {

        public static final Subjects ANYONE = new Subjects(List.of(), List.of(), false);

        public Subjects {
            roles = List.copyOf(roles);
            consumers = List.copyOf(consumers);
        }

        public boolean anyone() {
            return roles.isEmpty() && consumers.isEmpty() && !anyAuthenticated;
        }
    }
}
