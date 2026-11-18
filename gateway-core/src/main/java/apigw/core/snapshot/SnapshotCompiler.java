package apigw.core.snapshot;

import apigw.core.plugin.LogPlugin;
import apigw.core.plugin.Phase;
import apigw.core.plugin.Plugin;
import apigw.core.plugin.PluginConfigException;
import apigw.core.plugin.PluginFactory;
import apigw.core.plugin.PluginRegistry;
import apigw.core.plugin.RequestPlugin;
import apigw.core.plugin.ResponseHeadersPlugin;
import apigw.core.routing.MethodBits;
import apigw.core.routing.RouteTrie;
import apigw.core.routing.Router;
import apigw.model.Cidr;
import apigw.model.ConfigError;
import apigw.model.ConfigException;
import apigw.model.ConfigValidator;
import apigw.model.GatewayConfig;
import apigw.model.PathMatch;
import apigw.model.PluginRef;
import apigw.model.Route;
import apigw.model.Service;
import apigw.model.Settings;
import apigw.model.Upstream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns a validated {@link GatewayConfig} into an immutable {@link Snapshot}. Runs off the event
 * loop. Either the whole config compiles or a {@link ConfigException} lists every problem; nothing
 * partial is ever returned.
 *
 * <p>An instance joins a phase only if its factory declares the phase <em>and</em> the instance
 * implements that phase's interface, so a factory can return a narrower instance for a narrower
 * config (nothing runs where nothing is configured).
 *
 * <p>Plugin precedence: route &gt; service &gt; global (consumer-level overrides arrive with M4).
 * A plugin of a given name is instantiated once per distinct scope config and shared by routes.
 */
public final class SnapshotCompiler {

    private static final AtomicLong GENERATION = new AtomicLong();

    private final PluginRegistry registry;

    public SnapshotCompiler(PluginRegistry registry) {
        this.registry = registry;
    }

    public Snapshot compile(GatewayConfig config) throws ConfigException {
        ConfigValidator.validate(config);
        List<ConfigError> errors = new ArrayList<>();
        Map<PluginRef, Plugin> instances = new HashMap<>();

        Map<String, CompiledUpstream> upstreams = new LinkedHashMap<>();
        for (Upstream u : config.upstreams()) {
            upstreams.put(u.id(), upstream(u));
        }
        Map<String, Service> services = new HashMap<>();
        for (Service s : config.services()) {
            services.put(s.id(), s);
        }

        Settings settings = config.settings();
        List<Resolved> global = resolve(config.globalPlugins(), "globalPlugins", instances, errors);
        Map<String, List<Resolved>> servicePlugins = new HashMap<>();
        for (int i = 0; i < config.services().size(); i++) {
            Service s = config.services().get(i);
            servicePlugins.put(s.id(), resolve(s.plugins(), "services[" + i + "].plugins", instances, errors));
        }

        Map<String, RouteTrie.Builder> exact = new HashMap<>();
        Map<String, RouteTrie.Builder> wildcard = new HashMap<>();
        RouteTrie.Builder anyHost = null;
        for (int i = 0; i < config.routes().size(); i++) {
            Route r = config.routes().get(i);
            Service s = services.get(r.serviceId());
            List<Resolved> routePlugins = resolve(r.plugins(), "routes[" + i + "].plugins", instances, errors);
            CompiledRoute cr = route(r, s, upstreams.get(s.upstreamId()), settings,
                    merge(global, servicePlugins.get(s.id()), routePlugins));
            var entry = new RouteTrie.Builder.Entry(cr, r.path(), r.match(), MethodBits.of(r.methods()),
                    r.headers(), r.query(), r.priority());
            if (r.hosts().isEmpty()) {
                if (anyHost == null) {
                    anyHost = new RouteTrie.Builder();
                }
                anyHost.add(entry);
            }
            for (String h : r.hosts()) {
                if (h.startsWith("*.")) {
                    wildcard.computeIfAbsent(h.substring(1), k -> new RouteTrie.Builder()).add(entry);
                } else {
                    exact.computeIfAbsent(h, k -> new RouteTrie.Builder()).add(entry);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ConfigException(errors);
        }
        Map<String, RouteTrie> exactTries = new HashMap<>();
        exact.forEach((k, v) -> exactTries.put(k, v.build()));
        Map<String, RouteTrie> wildTries = new HashMap<>();
        wildcard.forEach((k, v) -> wildTries.put(k, v.build()));
        Router router = new Router(exactTries, wildTries, anyHost == null ? null : anyHost.build());

        List<Cidr> trusted = settings.trustedProxies().stream().map(Cidr::parse).toList();
        CompiledSettings cs = CompiledSettings.of(trusted, java.util.Set.copyOf(settings.stripRequestHeaders()),
                settings.viaName(), settings.maxRequestBodyBytes());
        return new Snapshot(GENERATION.incrementAndGet(), config.revision(), cs, router, upstreams,
                config.routes().size());
    }

    private record Resolved(PluginFactory factory, PluginRef ref, Plugin instance) {
    }

    private List<Resolved> resolve(List<PluginRef> refs, String path, Map<PluginRef, Plugin> instances,
            List<ConfigError> errors) {
        List<Resolved> out = new ArrayList<>();
        for (int i = 0; i < refs.size(); i++) {
            PluginRef ref = refs.get(i);
            String p = path + "[" + i + "]";
            var factory = registry.find(ref.name());
            if (factory.isEmpty()) {
                errors.add(new ConfigError(p + ".name", "unknown plugin '" + ref.name() + "'"));
                continue;
            }
            Plugin instance = null;
            if (ref.enabled()) {
                instance = instances.get(ref);
                if (instance == null) {
                    try {
                        instance = factory.get().create(ref.config());
                        instances.put(ref, instance);
                    } catch (PluginConfigException e) {
                        for (ConfigError err : e.errors()) {
                            errors.add(new ConfigError(p + ".config" + (err.path().isEmpty() ? "" : "." + err.path()),
                                    err.message()));
                        }
                        continue;
                    }
                }
            }
            out.add(new Resolved(factory.get(), ref, instance));
        }
        return out;
    }

    /** Narrower scope replaces wider by plugin name; a disabled narrower entry removes the plugin. */
    @SafeVarargs
    private static List<Resolved> merge(List<Resolved>... scopes) {
        Map<String, Resolved> byName = new LinkedHashMap<>();
        for (List<Resolved> scope : scopes) {
            for (Resolved r : scope) {
                byName.put(r.factory().name(), r);
            }
        }
        List<Resolved> out = new ArrayList<>();
        for (Resolved r : byName.values()) {
            if (r.instance() != null) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingInt((Resolved r) -> -r.factory().priority())
                .thenComparing(r -> r.factory().name()));
        return out;
    }

    private static CompiledRoute route(Route r, Service s, CompiledUpstream u, Settings settings,
            List<Resolved> plugins) {
        ResolvedTimeouts t = ResolvedTimeouts.of(r.timeouts().orElse(s.timeouts()).orElse(settings.defaultTimeouts())
                .orElse(apigw.model.Timeouts.DEFAULTS));
        List<String> params = new ArrayList<>();
        if (r.match() == PathMatch.TEMPLATE) {
            for (String seg : r.path().split("/")) {
                if (seg.startsWith("{")) {
                    params.add(seg.substring(1, seg.length() - 1));
                }
            }
        }
        ResponseHeadersPlugin[] headerFilter = plugins.stream()
                .filter(p -> p.factory().phases().contains(Phase.HEADER_FILTER)
                        && p.instance() instanceof ResponseHeadersPlugin)
                .map(p -> (ResponseHeadersPlugin) p.instance()).toArray(ResponseHeadersPlugin[]::new);
        LogPlugin[] log = plugins.stream()
                .filter(p -> p.factory().phases().contains(Phase.LOG) && p.instance() instanceof LogPlugin)
                .map(p -> (LogPlugin) p.instance()).toArray(LogPlugin[]::new);
        return new CompiledRoute(r.id(), s.id(), u, t, r.preserveHost(), params,
                request(plugins, Phase.REWRITE), request(plugins, Phase.ACCESS), request(plugins, Phase.BEFORE_PROXY),
                headerFilter, log);
    }

    private static RequestPlugin[] request(List<Resolved> plugins, Phase phase) {
        return plugins.stream()
                .filter(p -> p.factory().phases().contains(phase) && p.instance() instanceof RequestPlugin)
                .map(p -> (RequestPlugin) p.instance()).toArray(RequestPlugin[]::new);
    }

    private static CompiledUpstream upstream(Upstream u) {
        List<CompiledUpstream.Target> targets = new ArrayList<>();
        for (int i = 0; i < u.targets().size(); i++) {
            Upstream.Target t = u.targets().get(i);
            String host = t.host().indexOf(':') >= 0 ? "[" + t.host() + "]" : t.host();
            targets.add(new CompiledUpstream.Target(i, t.host(), t.port(), t.weight(), host + ":" + t.port()));
        }
        String hashHeader = u.hashKey().startsWith("header:")
                ? u.hashKey().substring(7).toLowerCase(java.util.Locale.ROOT) : null;
        String key = u.scheme() + "|" + u.caFile().map(Object::toString).orElse("") + "|" + u.http2() + "|"
                + u.pool() + "|" + u.targets();
        return new CompiledUpstream(u.id(), u.scheme(), u.caFile(), targets, u.balancer(), hashHeader, u.pool(),
                u.http2(), key);
    }
}
