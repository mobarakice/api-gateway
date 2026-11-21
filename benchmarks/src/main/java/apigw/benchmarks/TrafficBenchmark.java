package apigw.benchmarks;

import apigw.core.auth.Principal;
import apigw.core.plugin.Exchange;
import apigw.core.plugin.Outcome;
import apigw.core.plugin.PluginServices;
import apigw.core.plugin.RequestPlugin;
import apigw.core.plugin.ResponseHeadersPlugin;
import apigw.core.plugin.ResponseTap;
import apigw.core.routing.RouteMatch;
import apigw.core.snapshot.CompiledRoute;
import apigw.core.snapshot.Snapshot;
import apigw.core.snapshot.SnapshotCompiler;
import apigw.model.ConfigDecoder;
import apigw.plugins.BuiltinPlugins;
import apigw.plugins.cache.ResponseCache;
import apigw.plugins.ratelimit.RateLimitStore;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Per-request cost of the M5 traffic plugins, as compiled into a route chain: IP rules, CORS on an
 * actual response, a local token-bucket check (allowed), and a cache hit (key build, L1 lookup, purge
 * check, response headers). The exchange is a minimal stand-in whose request is a JDK dynamic proxy:
 * each request accessor costs a reflective dispatch, so these numbers are an upper bound for the
 * plugin logic itself.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class TrafficBenchmark {

    private BenchExchange ipEx;
    private BenchExchange corsEx;
    private BenchExchange rlEx;
    private BenchExchange cacheEx;
    private RequestPlugin ip;
    private ResponseHeadersPlugin cors;
    private RequestPlugin rateLimit;
    private RequestPlugin cache;
    private MultiMap corsHeaders;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        String json = "{\"schemaVersion\":1,\"settings\":{\"upstreamAllowCidrs\":[\"127.0.0.0/8\"]},"
                + "\"upstreams\":[{\"id\":\"u\",\"targets\":[{\"host\":\"127.0.0.1\",\"port\":1}]}],"
                + "\"services\":[{\"id\":\"s\",\"upstreamId\":\"u\"}],\"routes\":["
                + "{\"id\":\"ip\",\"path\":\"/ip\",\"serviceId\":\"s\",\"plugins\":[{\"name\":\"ip-restriction\","
                + "\"config\":{\"allow\":[\"10.0.0.0/8\",\"172.16.0.0/12\",\"192.168.0.0/16\"],"
                + "\"deny\":[\"10.6.6.0/24\"]}}]},"
                + "{\"id\":\"cors\",\"path\":\"/cors\",\"serviceId\":\"s\",\"plugins\":[{\"name\":\"cors\",\"config\":"
                + "{\"allowOrigins\":[\"https://a.test\",\"https://*.example.test\"],"
                + "\"exposeHeaders\":[\"x-request-id\"]}}]},"
                + "{\"id\":\"rl\",\"path\":\"/rl\",\"serviceId\":\"s\","
                + "\"plugins\":[{\"name\":\"rate-limit\",\"config\":"
                + "{\"limit\":100000000,\"windowSeconds\":1,\"headers\":true}}]},"
                + "{\"id\":\"c\",\"path\":\"/c\",\"serviceId\":\"s\",\"plugins\":[{\"name\":\"cache\",\"config\":"
                + "{\"vary\":[\"accept\"]}}]}]}";
        Snapshot snap = new SnapshotCompiler(BuiltinPlugins.registry(), new PluginServices(Map.of(
                RateLimitStore.class, new RateLimitStore(10_000, null),
                ResponseCache.class, new ResponseCache(16L << 20, null))), Clock.systemUTC())
                .compile(ConfigDecoder.decode(json.getBytes(StandardCharsets.UTF_8)));
        ipEx = new BenchExchange(snap, route(snap, "/ip/x"), "10.1.2.3", "/ip/x", null, Map.of());
        corsEx = new BenchExchange(snap, route(snap, "/cors/x"), "10.1.2.3", "/cors/x", null,
                Map.of("origin", "https://app.example.test"));
        rlEx = new BenchExchange(snap, route(snap, "/rl/x"), "10.1.2.3", "/rl/x", null, Map.of());
        cacheEx = new BenchExchange(snap, route(snap, "/c/items"), "10.1.2.3", "/c/items", "page=2&sort=asc",
                Map.of("accept", "application/json"));
        ip = ipEx.route().requestChain()[0];
        cors = corsEx.route().headerFilter()[0];
        rateLimit = rlEx.route().requestChain()[0];
        cache = cacheEx.route().requestChain()[0];
        corsHeaders = MultiMap.caseInsensitiveMultiMap();
        // Warm the cache: the first request leads, its tap stores the response; later ones hit.
        if (cache.onRequest(cacheEx) != Outcome.CONTINUE || cacheEx.tap == null) {
            throw new IllegalStateException("cache did not lead the first request");
        }
        MultiMap h = MultiMap.caseInsensitiveMultiMap().add("cache-control", "max-age=3600")
                .add("content-type", "application/json");
        cacheEx.tap.onHead(200, h);
        cacheEx.tap.onChunk(Buffer.buffer("{\"items\":[1,2,3]}"));
        cacheEx.tap.onEnd();
        if (cache.onRequest(cacheEx) != Outcome.STOP || cacheEx.served != 200) {
            throw new IllegalStateException("cache did not hit");
        }
        if (ip.onRequest(ipEx) != Outcome.CONTINUE || rateLimit.onRequest(rlEx) != Outcome.CONTINUE) {
            throw new IllegalStateException("plugin did not admit the benchmark request");
        }
    }

    private static CompiledRoute route(Snapshot s, String path) {
        RouteMatch m = new RouteMatch();
        if (!s.router().match("bench.test", "GET", path, null, MultiMap.caseInsensitiveMultiMap(), m)) {
            throw new IllegalStateException("no route for " + path);
        }
        return m.route();
    }

    @Benchmark
    public Outcome ipRestriction() {
        return ip.onRequest(ipEx);
    }

    @Benchmark
    public MultiMap corsResponseHeaders() {
        corsHeaders.clear();
        cors.onResponseHeaders(corsEx, 200, corsHeaders);
        return corsHeaders;
    }

    @Benchmark
    public Outcome rateLimitLocalTokenBucket() {
        rlEx.extra = null;
        return rateLimit.onRequest(rlEx);
    }

    @Benchmark
    public Outcome cacheHit() {
        return cache.onRequest(cacheEx);
    }

    /** Just enough of an exchange for the plugins under test. Single-threaded (one JMH thread). */
    static final class BenchExchange implements Exchange {
        private final Snapshot snapshot;
        private final CompiledRoute route;
        private final String ip;
        private final HttpServerRequest request;
        private final MultiMap upstream = MultiMap.caseInsensitiveMultiMap();
        ResponseTap tap;
        MultiMap extra;
        int served;

        BenchExchange(Snapshot snapshot, CompiledRoute route, String ip, String path, String query,
                Map<String, String> headers) {
            this.snapshot = snapshot;
            this.route = route;
            this.ip = ip;
            MultiMap h = MultiMap.caseInsensitiveMultiMap();
            headers.forEach(h::add);
            this.request = (HttpServerRequest) Proxy.newProxyInstance(TrafficBenchmark.class.getClassLoader(),
                    new Class<?>[] {HttpServerRequest.class}, (proxy, m, args) -> switch (m.getName()) {
                        case "path" -> path;
                        case "query" -> query;
                        case "method" -> HttpMethod.GET;
                        case "headers" -> h;
                        case "getHeader" -> h.get(args[0].toString());
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }

        @Override
        public HttpServerRequest request() {
            return request;
        }

        @Override
        public CompiledRoute route() {
            return route;
        }

        @Override
        public String pathParam(String name) {
            return null;
        }

        @Override
        public MultiMap upstreamHeaders() {
            return upstream;
        }

        @Override
        public String upstreamUri() {
            return request.path();
        }

        @Override
        public void upstreamUri(String uri) {
            // Not exercised by the benchmarked plugins.
        }

        @Override
        public String clientIp() {
            return ip;
        }

        @Override
        public String requestId() {
            return "0123456789abcdef";
        }

        @Override
        public Outcome respond(int status, String title) {
            served = status;
            return Outcome.STOP;
        }

        @Override
        public void resume() {
            // Benchmarked paths complete synchronously.
        }

        @Override
        public Snapshot snapshot() {
            return snapshot;
        }

        @Override
        public Principal principal() {
            return null;
        }

        @Override
        public Outcome authenticate(Principal principal) {
            return Outcome.CONTINUE;
        }

        @Override
        public Outcome respond(int status, String title, String headerName, String headerValue) {
            return respond(status, title);
        }

        @Override
        public Outcome respond(int status, String title, MultiMap headers) {
            return respond(status, title);
        }

        @Override
        public Outcome respondWith(int status, MultiMap headers, Buffer body) {
            served = status;
            return Outcome.STOP;
        }

        @Override
        public void tapResponse(ResponseTap t) {
            tap = t;
        }

        @Override
        public void addResponseHeader(CharSequence name, CharSequence value) {
            if (extra == null) {
                extra = MultiMap.caseInsensitiveMultiMap();
            }
            extra.add(name, value);
        }

        @Override
        public void limitRequestBody(long maxBytes) {
            // Not exercised by the benchmarked plugins.
        }
    }
}
