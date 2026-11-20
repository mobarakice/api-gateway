package apigw.model;

import java.util.List;

/** A logical backend: an upstream plus defaults shared by the routes that use it. */
public record Service(String id, String upstreamId, Timeouts timeouts, List<PluginRef> plugins, String tenantId) {

    public Service {
        plugins = List.copyOf(plugins);
    }
}
