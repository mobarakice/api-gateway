package apigw.model;

import java.util.List;
import java.util.Map;

/**
 * An API client: a person, an application or a service. Credentials point at a consumer; once
 * authenticated, the consumer's roles and attributes feed authorization and its plugins override the
 * route's (precedence consumer &gt; route &gt; service &gt; global, ADR 0016).
 *
 * @param tenantId   owning tenant; null = not tenant-scoped (may only call routes without a tenant)
 * @param roles      role names for RBAC
 * @param attributes string attributes for ABAC ({@code principal.attr.<name>})
 * @param plugins    per-consumer overrides of plugins in the route's chain (never authentication plugins)
 */
public record Consumer(String id, String tenantId, String username, List<String> roles,
        Map<String, String> attributes, List<PluginRef> plugins) {

    public Consumer {
        roles = List.copyOf(roles);
        attributes = Map.copyOf(attributes);
        plugins = List.copyOf(plugins);
    }
}
