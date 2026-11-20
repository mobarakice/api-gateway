package apigw.it;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/** Helpers shared by the etcd config-worker suites: envelopes, an echo upstream, gateway env, polling. */
final class EtcdGatewaySupport {

    static final Path TLS = Path.of(System.getProperty("gw.test.tls.dir"));
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private EtcdGatewaySupport() {
    }

    /** etcdctl as root: {@code args -> stdout}. */
    interface Ctl extends Function<String[], String> {
        default void put(String key, String value) {
            apply(new String[] {"put", key, value});
        }

        default void del(String key) {
            apply(new String[] {"del", key});
        }

        default String get(String key) {
            return apply(new String[] {"get", "--print-value-only", key});
        }
    }

    static String envelope(String kind, String id, String spec) {
        return "{\"id\":\"" + id + "\",\"kind\":\"" + kind + "\",\"schemaVersion\":1,\"updatedAt\":"
                + "\"2026-11-21T00:00:00Z\",\"updatedBy\":\"it\",\"spec\":" + spec + "}";
    }

    /** schema-version, upstream {@code echo}, service {@code s}, route {@code a} at {@code /a}. */
    static void seed(Ctl ctl, String prefix, int upstreamPort) {
        ctl.put(prefix + "meta/schema-version", "1");
        ctl.put(prefix + "upstreams/echo", envelope("Upstream", "echo",
                "{\"targets\":[{\"host\":\"127.0.0.1\",\"port\":" + upstreamPort + "}]}"));
        ctl.put(prefix + "services/s", envelope("Service", "s", "{\"upstreamId\":\"echo\"}"));
        putRoute(ctl, prefix, "a", "/a");
    }

    static void putRoute(Ctl ctl, String prefix, String id, String path) {
        ctl.put(prefix + "routes/" + id, envelope("Route", id, "{\"path\":\"" + path + "\",\"serviceId\":\"s\"}"));
    }

    /** A plain HTTP/1.1 upstream answering every request with 200 "echo". */
    static HttpServer echo() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            byte[] b = "echo".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        return s;
    }

    /** Ports and settings of one gateway process. */
    record Gw(int http, int status, Map<String, String> env) {
    }

    static Gw gatewayEnv(String endpoints, String prefix, String gatewayPassword, Path lkgDir, String gatewayId)
            throws IOException {
        Path pw = Files.createTempFile(lkgDir.toAbsolutePath().resolveSibling("."), "etcd-pw", ".txt");
        Files.writeString(pw, gatewayPassword + "\n");
        int http = Ports.free();
        int status = Ports.free();
        Map<String, String> env = new HashMap<>(Map.of("GW_BIND_HOST", "127.0.0.1",
                "GW_HTTP_PORT", Integer.toString(http), "GW_HTTPS_PORT", Integer.toString(Ports.free()),
                "GW_STATUS_PORT", Integer.toString(status), "GW_EVENT_LOOPS", "2",
                "GW_SHUTDOWN_GRACE_SECONDS", "2"));
        env.putAll(Map.of("GW_ETCD_ENDPOINTS", endpoints, "GW_ETCD_CA", TLS.resolve("ca.pem").toString(),
                "GW_ETCD_USER", "gateway", "GW_ETCD_PASSWORD_FILE", pw.toString(), "GW_ETCD_PREFIX", prefix,
                "GW_LKG_DIR", lkgDir.toString(), "GW_GATEWAY_ID", gatewayId, "GW_ETCD_TIMEOUT_MS", "3000"));
        return new Gw(http, status, env);
    }

    record Resp(int status, String body) {
    }

    static Resp get(int port, String path) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body());
        } catch (IOException e) {
            return new Resp(-1, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resp(-1, "interrupted");
        }
    }

    /** Polls until {@code check} holds; returns elapsed milliseconds, or fails with the last response. */
    static long await(int port, String path, Predicate<Resp> check, Duration timeout, GatewayProcess gw)
            throws InterruptedException {
        long t0 = System.nanoTime();
        long deadline = t0 + timeout.toNanos();
        Resp last = null;
        while (System.nanoTime() < deadline) {
            last = get(port, path);
            if (check.test(last)) {
                return (System.nanoTime() - t0) / 1_000_000;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("condition not met on " + path + " within " + timeout + "; last=" + last
                + "\ngateway output:\n" + String.join("\n", gw.output()));
    }

    static Predicate<Resp> status(int code) {
        return r -> r.status() == code;
    }

    static Predicate<Resp> configState(String state) {
        return r -> r.status() == 200 && r.body().contains("\"state\":\"" + state + "\"");
    }
}
