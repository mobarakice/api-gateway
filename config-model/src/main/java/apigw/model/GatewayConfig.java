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
        List<PluginRef> globalPlugins) {

    /** Highest schema version this build understands. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public GatewayConfig {
        routes = List.copyOf(routes);
        services = List.copyOf(services);
        upstreams = List.copyOf(upstreams);
        globalPlugins = List.copyOf(globalPlugins);
    }
}
