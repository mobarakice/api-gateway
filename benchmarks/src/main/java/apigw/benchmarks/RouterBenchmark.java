package apigw.benchmarks;

import apigw.core.plugin.PluginRegistry;
import apigw.core.routing.RouteMatch;
import apigw.core.routing.Router;
import apigw.core.snapshot.SnapshotCompiler;
import apigw.model.ConfigDecoder;
import io.vertx.core.MultiMap;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Route lookup cost at 10, 1K and 10K routes. Each "service" contributes three routes (exact,
 * template with one parameter, prefix) behind two hosts plus host-less routes, so lookups exercise
 * the host table, static and parameter branches, prefix fallback and misses. Run with
 * {@code -prof gc} to confirm the lookup allocates nothing.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsAppend = {"-XX:+UseParallelGC", "-Xms1g", "-Xmx1g"})
public class RouterBenchmark {

    @Param({"10", "1000", "10000"})
    public int routes;

    private static final int LOOKUPS = 4096;

    private Router router;
    private final RouteMatch match = new RouteMatch();
    private final MultiMap noHeaders = MultiMap.caseInsensitiveMultiMap();
    private String[] hosts;
    private String[] paths;
    private int next;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        int services = Math.max(1, routes / 3);
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < services; i++) {
            String host = i % 3 == 0 ? "" : ",\"hosts\":[\"" + (i % 3 == 1 ? "api.example.com" : "*.tenant.example")
                    + "\"]";
            r.append(i == 0 ? "" : ",")
                    .append("{\"id\":\"e").append(i).append("\",\"serviceId\":\"s\",\"match\":\"exact\",")
                    .append("\"path\":\"/svc").append(i).append("/health\"").append(host).append("},")
                    .append("{\"id\":\"t").append(i).append("\",\"serviceId\":\"s\",\"match\":\"template\",")
                    .append("\"methods\":[\"GET\",\"PUT\"],")
                    .append("\"path\":\"/svc").append(i).append("/v1/items/{id}\"").append(host).append("},")
                    .append("{\"id\":\"p").append(i).append("\",\"serviceId\":\"s\",")
                    .append("\"path\":\"/svc").append(i).append("/files\"").append(host).append('}');
        }
        String json = "{\"schemaVersion\":1,\"upstreams\":[{\"id\":\"u\",\"targets\":[{\"host\":\"127.0.0.1\","
                + "\"port\":1}]}],\"services\":[{\"id\":\"s\",\"upstreamId\":\"u\"}],\"routes\":[" + r + "]}";
        router = new SnapshotCompiler(new PluginRegistry(List.of()))
                .compile(ConfigDecoder.decode(json.getBytes(StandardCharsets.UTF_8))).router();
        SplittableRandom rnd = new SplittableRandom(42);
        hosts = new String[LOOKUPS];
        paths = new String[LOOKUPS];
        for (int k = 0; k < LOOKUPS; k++) {
            int i = rnd.nextInt(services);
            hosts[k] = i % 3 == 0 ? "other.org" : i % 3 == 1 ? "api.example.com" : "acme.tenant.example";
            paths[k] = switch (rnd.nextInt(4)) {
                case 0 -> "/svc" + i + "/health";
                case 1 -> "/svc" + i + "/v1/items/" + rnd.nextInt(1_000_000);
                case 2 -> "/svc" + i + "/files/a/b/c.txt";
                default -> "/svc" + i + "/missing/path";
            };
        }
    }

    @Benchmark
    public Object lookup() {
        int k = next;
        next = (k + 1) & (LOOKUPS - 1);
        boolean found = router.match(hosts[k], "GET", paths[k], null, noHeaders, match);
        return found ? match.route() : null;
    }
}
