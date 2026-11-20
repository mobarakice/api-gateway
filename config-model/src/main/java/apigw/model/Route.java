package apigw.model;

import java.util.List;
import java.util.Map;

/**
 * Matches requests and sends them to a service.
 *
 * @param hosts        exact ({@code api.example.com}) or wildcard ({@code *.example.com}) hosts; empty = any host
 * @param methods      upper-case methods; empty = any method
 * @param headers      request headers that must be present with exactly these values (names case-insensitive)
 * @param query        query parameters that must be present with exactly these values
 * @param priority     higher wins when several routes match the same path
 * @param timeouts     overrides the service's timeouts
 * @param plugins      route-level plugin configs (override service and global ones of the same name)
 * @param preserveHost forward the client's Host header instead of the target's host:port
 * @param tenantId     owning tenant; null = not tenant-scoped. A tenant route only serves principals of
 *                     that tenant and needs an authentication plugin (ADR 0016)
 */
public record Route(
        String id,
        List<String> hosts,
        List<String> methods,
        String path,
        PathMatch match,
        Map<String, String> headers,
        Map<String, String> query,
        int priority,
        String serviceId,
        Timeouts timeouts,
        List<PluginRef> plugins,
        boolean preserveHost,
        String tenantId) {

    public Route {
        hosts = List.copyOf(hosts);
        methods = List.copyOf(methods);
        headers = Map.copyOf(headers);
        query = Map.copyOf(query);
        plugins = List.copyOf(plugins);
    }
}
