package apigw.controlplane.auth;

import java.util.Set;

/**
 * Who is calling, after authentication: the token subject and its highest role. {@code sourceIp} and
 * {@code requestId} are recorded in audit events.
 */
public record Actor(String subject, Role role, Set<Role> roles, String sourceIp, String requestId) {

    public Actor {
        roles = Set.copyOf(roles);
    }

    public boolean may(Role required) {
        return role.includes(required);
    }
}
