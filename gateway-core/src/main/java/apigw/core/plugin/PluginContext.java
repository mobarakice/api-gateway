package apigw.core.plugin;

import apigw.model.AuthzPolicy;
import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a factory may see while one snapshot compiles: snapshot-wide data its config can reference
 * (authorization policies), long-lived services, and a clock. A factory registers long-lived objects
 * its instance depends on with {@link #use(Object)}, so services can release objects that no published
 * snapshot uses any more. Not thread-safe; one per compile.
 */
public final class PluginContext {

    private final Map<String, AuthzPolicy> authzPolicies;
    private final PluginServices services;
    private final Clock clock;
    private final Set<Object> resources = new HashSet<>();

    public PluginContext(Map<String, AuthzPolicy> authzPolicies, PluginServices services, Clock clock) {
        this.authzPolicies = Map.copyOf(authzPolicies);
        this.services = services;
        this.clock = clock;
    }

    /** For tests and factories that need nothing. */
    public static PluginContext empty() {
        return new PluginContext(Map.of(), PluginServices.NONE, Clock.systemUTC());
    }

    public Map<String, AuthzPolicy> authzPolicies() {
        return authzPolicies;
    }

    public PluginServices services() {
        return services;
    }

    public Clock clock() {
        return clock;
    }

    public void use(Object resource) {
        resources.add(resource);
    }

    public Set<Object> resources() {
        return Set.copyOf(resources);
    }
}
