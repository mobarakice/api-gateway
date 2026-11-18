package apigw.core.snapshot;

import apigw.core.plugin.LogPlugin;
import apigw.core.plugin.RequestPlugin;
import apigw.core.plugin.ResponseHeadersPlugin;
import java.util.List;

/**
 * A route with everything resolved at compile time: its upstream, timeouts and the plugin chain
 * per phase (empty arrays when nothing applies, so a disabled feature costs nothing).
 * Arrays are never modified after construction.
 */
public final class CompiledRoute {

    private final String id;
    private final String serviceId;
    private final CompiledUpstream upstream;
    private final ResolvedTimeouts timeouts;
    private final boolean preserveHost;
    private final List<String> paramNames;
    final RequestPlugin[] rewrite;
    final RequestPlugin[] access;
    final RequestPlugin[] beforeProxy;
    final ResponseHeadersPlugin[] headerFilter;
    final LogPlugin[] log;
    private final RequestPlugin[] requestChain;

    CompiledRoute(String id, String serviceId, CompiledUpstream upstream, ResolvedTimeouts timeouts,
            boolean preserveHost, List<String> paramNames, RequestPlugin[] rewrite, RequestPlugin[] access,
            RequestPlugin[] beforeProxy, ResponseHeadersPlugin[] headerFilter, LogPlugin[] log) {
        this.id = id;
        this.serviceId = serviceId;
        this.upstream = upstream;
        this.timeouts = timeouts;
        this.preserveHost = preserveHost;
        this.paramNames = List.copyOf(paramNames);
        this.rewrite = rewrite;
        this.access = access;
        this.beforeProxy = beforeProxy;
        this.headerFilter = headerFilter;
        this.log = log;
        this.requestChain = concat(rewrite, access, beforeProxy);
    }

    public String id() {
        return id;
    }

    public String serviceId() {
        return serviceId;
    }

    public CompiledUpstream upstream() {
        return upstream;
    }

    public ResolvedTimeouts timeouts() {
        return timeouts;
    }

    public boolean preserveHost() {
        return preserveHost;
    }

    /** Template parameter names in path order (empty for non-template routes). */
    public List<String> paramNames() {
        return paramNames;
    }

    /**
     * Request-phase plugins in execution order: rewrite, access, before-proxy concatenated.
     * The returned array is shared; callers must not modify it.
     */
    public RequestPlugin[] requestChain() {
        return requestChain;
    }

    public ResponseHeadersPlugin[] headerFilter() {
        return headerFilter;
    }

    public LogPlugin[] logPlugins() {
        return log;
    }

    private static RequestPlugin[] concat(RequestPlugin[] rewrite, RequestPlugin[] access,
            RequestPlugin[] beforeProxy) {
        RequestPlugin[] all = new RequestPlugin[rewrite.length + access.length + beforeProxy.length];
        System.arraycopy(rewrite, 0, all, 0, rewrite.length);
        System.arraycopy(access, 0, all, rewrite.length, access.length);
        System.arraycopy(beforeProxy, 0, all, rewrite.length + access.length, beforeProxy.length);
        return all;
    }
}
