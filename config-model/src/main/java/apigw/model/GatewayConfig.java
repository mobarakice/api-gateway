package apigw.model;

import java.util.List;

/**
 * A complete configuration at one etcd revision (or from a snapshot file). This is the unit the
 * gateway validates, compiles and swaps; it is never applied partially.
 *
 * @param schemaVersion key/value schema version ({@code meta/schema-version})
 * @param revision      etcd revision this config was read at; 0 for a file without one
 */
public record GatewayConfig(
        int schemaVersion,
        long revision,
        Settings settings,
        List<Route> routes,
        List<Service> services,
        List<Upstream> upstreams,
        List<PluginRef> globalPlugins,
        List<Consumer> consumers,
        List<Credential> credentials,
        List<AuthzPolicy> authzPolicies,
        List<PluginConfigSet> pluginConfigs,
        List<RevokedToken> revokedTokens) {

    /** Highest schema version this build understands. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public GatewayConfig {
        routes = List.copyOf(routes);
        services = List.copyOf(services);
        upstreams = List.copyOf(upstreams);
        globalPlugins = List.copyOf(globalPlugins);
        consumers = List.copyOf(consumers);
        credentials = List.copyOf(credentials);
        authzPolicies = List.copyOf(authzPolicies);
        pluginConfigs = List.copyOf(pluginConfigs);
        revokedTokens = List.copyOf(revokedTokens);
    }

    /** Routing objects only (no security objects); convenient for tests and simple file configs. */
    public GatewayConfig(int schemaVersion, long revision, Settings settings, List<Route> routes,
            List<Service> services, List<Upstream> upstreams, List<PluginRef> globalPlugins) {
        this(schemaVersion, revision, settings, routes, services, upstreams, globalPlugins, List.of(), List.of(),
                List.of(), List.of(), List.of());
    }
}
