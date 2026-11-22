package apigw.core.snapshot;

import apigw.core.observe.RouteTelemetry;
import apigw.core.observe.RouteTelemetryFactory;
import apigw.core.auth.Attributes;
import apigw.core.auth.CompiledConsumer;
import apigw.core.auth.SecurityIndex;
import apigw.core.plugin.LogPlugin;
import apigw.core.plugin.Phase;
import apigw.core.plugin.Plugin;
import apigw.core.plugin.PluginConfigException;
import apigw.core.plugin.PluginContext;
import apigw.core.plugin.PluginFactory;
import apigw.core.plugin.PluginRegistry;
import apigw.core.plugin.PluginServices;
import apigw.core.plugin.RequestPlugin;
import apigw.core.plugin.ResponseHeadersPlugin;
import apigw.core.routing.MethodBits;
import apigw.core.routing.RouteTrie;
import apigw.core.routing.Router;
import apigw.model.ApiKeyHash;
import apigw.model.AuthzPolicy;
import apigw.model.Cidr;
import apigw.model.ConfigError;
import apigw.model.ConfigException;
import apigw.model.ConfigValidator;
import apigw.model.Consumer;
import apigw.model.Credential;
import apigw.model.GatewayConfig;
import apigw.model.PathMatch;
import apigw.model.PluginConfigSet;
import apigw.model.PluginRef;
import apigw.model.RevokedToken;
import apigw.model.Route;
import apigw.model.Service;
import apigw.model.Settings;
import apigw.model.Upstream;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>Plugin precedence: consumer &gt; route &gt; service &gt; global. Consumer overrides are applied per
 * request after authentication by swapping the instance in the same chain slot (ADR 0016).
 * A plugin of a given name is instantiated once per distinct scope config and shared by routes.
 */
public final class SnapshotCompiler {

    private static final AtomicLong GENERATION = new AtomicLong();

    private final PluginRegistry registry;
    private final PluginServices services;
    private final Clock clock;

    public SnapshotCompiler(PluginRegistry registry) {
        this(registry, PluginServices.NONE, Clock.systemUTC());
    }

    public SnapshotCompiler(PluginRegistry registry, PluginServices services, Clock clock) {
        this.registry = registry;
        this.services = services;
        this.clock = clock;
    }

    public PluginServices services() {
        return services;
    }

    public Snapshot compile(GatewayConfig config) throws ConfigException {
        ConfigValidator.validate(config);
        List<ConfigError> errors = new ArrayList<>();
        Map<PluginRef, Plugin> instances = new HashMap<>();
        Map<String, AuthzPolicy> policies = new HashMap<>();
        config.authzPolicies().forEach(a -> policies.put(a.id(), a));
        PluginContext ctx = new PluginContext(policies, services, clock);
        Map<String, PluginConfigSet> pluginConfigs = new HashMap<>();
        config.pluginConfigs().forEach(pc -> pluginConfigs.put(pc.id(), pc));
        Compile c = new Compile(ctx, pluginConfigs, instances, errors);

        Map<String, CompiledUpstream> upstreams = new LinkedHashMap<>();
        for (Upstream u : config.upstreams()) {
            upstreams.put(u.id(), upstream(u));
        }
        Map<String, Service> services = new HashMap<>();
        for (Service s : config.services()) {
            services.put(s.id(), s);
        }

        Settings settings = config.settings();
        List<Resolved> global = resolve(config.globalPlugins(), "globalPlugins", c);
        Map<String, List<Resolved>> servicePlugins = new HashMap<>();
        for (int i = 0; i < config.services().size(); i++) {
            Service s = config.services().get(i);
            servicePlugins.put(s.id(), resolve(s.plugins(), "services[" + i + "].plugins", c));
        }

        Map<String, RouteTrie.Builder> exact = new HashMap<>();
        Map<String, RouteTrie.Builder> wildcard = new HashMap<>();
        RouteTrie.Builder anyHost = null;
        for (int i = 0; i < config.routes().size(); i++) {
            Route r = config.routes().get(i);
            Service s = services.get(r.serviceId());
            List<Resolved> routePlugins = resolve(r.plugins(), "routes[" + i + "].plugins", c);
            List<Resolved> chain = merge(global, servicePlugins.get(s.id()), routePlugins);
            if (r.tenantId() != null && chain.stream().noneMatch(x -> x.factory().authenticates())) {
                errors.add(new ConfigError("routes[" + i + "]", "tenant-scoped routes need an authentication plugin "
                        + "(at route, service or global scope)"));
            }
            CompiledRoute cr = route(r, s, upstreams.get(s.upstreamId()), settings, chain);
            // Lets the telemetry service drop meters of routes the published snapshot no longer has.
            ctx.use(cr.telemetry());
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
        SecurityIndex security = security(config, c);
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
                settings.viaName(), settings.maxRequestBodyBytes(),
                settings.upstreamAllowCidrs().stream().map(Cidr::parse).toList());
        return new Snapshot(GENERATION.incrementAndGet(), config.revision(), cs, router, upstreams,
                config.routes().size(), security, ctx.resources(), CachePurges.of(config.cachePurges()));
    }

    /** Per-compile state shared by the resolve calls. */
    private record Compile(PluginContext ctx, Map<String, PluginConfigSet> pluginConfigs,
            Map<PluginRef, Plugin> instances, List<ConfigError> errors) {
    }

    private SecurityIndex security(GatewayConfig config, Compile c) {
        Map<String, CompiledConsumer> consumers = new HashMap<>();
        for (int i = 0; i < config.consumers().size(); i++) {
            Consumer k = config.consumers().get(i);
            String p = "consumers[" + i + "].plugins";
            Plugin[] overrides = null;
            List<Resolved> resolved = resolve(k.plugins(), p, c);
            for (int j = 0; j < resolved.size(); j++) {
                Resolved r = resolved.get(j);
                if (r.factory().authenticates()) {
                    c.errors().add(new ConfigError(p + "[" + j + "].name", "authentication plugins cannot be set "
                            + "per consumer (the consumer is only known after authentication)"));
                    continue;
                }
                if (overrides == null) {
                    overrides = new Plugin[registry.size()];
                }
                overrides[registry.ordinal(r.factory().name())] = r.instance() == null
                        ? CompiledConsumer.DISABLED : r.instance();
            }
            consumers.put(k.id(), new CompiledConsumer(k.id(), k.tenantId(), k.username(), Set.copyOf(k.roles()),
                    Attributes.of(k.attributes()), overrides));
        }
        Map<String, SecurityIndex.ApiKey> apiKeys = new HashMap<>();
        for (Credential cred : config.credentials()) {
            if (cred.type() == Credential.Type.API_KEY) {
                ApiKeyHash.Parsed h = ApiKeyHash.parse(cred.hash());
                apiKeys.put(cred.keyId(), new SecurityIndex.ApiKey(cred.consumerId() + "/" + cred.id(),
                        consumers.get(cred.consumerId()), h.salt(), h.digest()));
            }
        }
        Map<String, SecurityIndex.Revocation> revoked = new HashMap<>();
        for (RevokedToken t : config.revokedTokens()) {
            revoked.put(t.jti(), new SecurityIndex.Revocation(t.issuer(), t.expiresAt()));
        }
        return new SecurityIndex(consumers, apiKeys, revoked);
    }

    private record Resolved(PluginFactory factory, PluginRef ref, Plugin instance) {
    }

    private List<Resolved> resolve(List<PluginRef> refs, String path, Compile c) {
        Map<PluginRef, Plugin> instances = c.instances();
        List<ConfigError> errors = c.errors();
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
                        JsonNode config = ref.config();
                        if (ref.configRef() != null) {
                            config = c.pluginConfigs().get(ref.configRef()).config();
                        }
                        instance = factory.get().create(config, c.ctx());
                        instances.put(ref, instance);
                    } catch (PluginConfigException e) {
                        for (ConfigError err : e.errors()) {
                            errors.add(new ConfigError((ref.configRef() != null
                                    ? "pluginConfigs." + ref.configRef() + ".config" : p + ".config")
                                    + (err.path().isEmpty() ? "" : "." + err.path()), err.message()));
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

    private CompiledRoute route(Route r, Service s, CompiledUpstream u, Settings settings,
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
        List<Resolved> headerList = plugins.stream()
                .filter(p -> p.factory().phases().contains(Phase.HEADER_FILTER)
                        && p.instance() instanceof ResponseHeadersPlugin).toList();
        List<Resolved> logList = plugins.stream()
                .filter(p -> p.factory().phases().contains(Phase.LOG) && p.instance() instanceof LogPlugin).toList();
        List<Resolved> requestList = new ArrayList<>();
        requestList.addAll(request(plugins, Phase.REWRITE));
        requestList.addAll(request(plugins, Phase.ACCESS));
        requestList.addAll(request(plugins, Phase.BEFORE_PROXY));
        return new CompiledRoute(r.id(), s.id(), u, t, RetryPolicy.of(r.retries() != null ? r.retries() : s.retries()),
                r.preserveHost(), params,
                instances(request(plugins, Phase.REWRITE), RequestPlugin.class, RequestPlugin[]::new),
                instances(request(plugins, Phase.ACCESS), RequestPlugin.class, RequestPlugin[]::new),
                instances(request(plugins, Phase.BEFORE_PROXY), RequestPlugin.class, RequestPlugin[]::new),
                instances(headerList, ResponseHeadersPlugin.class, ResponseHeadersPlugin[]::new),
                instances(logList, LogPlugin.class, LogPlugin[]::new),
                slots(requestList), slots(headerList), slots(logList), r.tenantId(), r.path(),
                services.find(RouteTelemetryFactory.class).map(f -> f.forRoute(r.id(), u.id()))
                        .orElse(RouteTelemetry.NONE));
    }

    private static List<Resolved> request(List<Resolved> plugins, Phase phase) {
        return plugins.stream()
                .filter(p -> p.factory().phases().contains(phase) && p.instance() instanceof RequestPlugin).toList();
    }

    private static <T> T[] instances(List<Resolved> list, Class<T> type, java.util.function.IntFunction<T[]> array) {
        T[] out = array.apply(list.size());
        for (int i = 0; i < out.length; i++) {
            out[i] = type.cast(list.get(i).instance());
        }
        return out;
    }

    private int[] slots(List<Resolved> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = registry.ordinal(list.get(i).factory().name());
        }
        return out;
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
        // Everything the per-loop pool, balancer, breaker and health state depend on: a change rebuilds them.
        String key = u.scheme() + "|" + u.caFile().map(Object::toString).orElse("") + "|" + u.http2() + "|"
                + u.pool() + "|" + u.targets() + "|" + u.balancer() + "|" + u.hashKey() + "|" + u.healthCheck() + "|"
                + u.circuitBreaker() + "|" + u.clientCert();
        return new CompiledUpstream(u.id(), u.scheme(), u.caFile(), targets, u.balancer(), hashHeader, u.pool(),
                u.http2(), u.healthCheck(), u.circuitBreaker(), u.clientCert(), key);
    }
}
