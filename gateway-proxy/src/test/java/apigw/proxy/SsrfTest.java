package apigw.proxy;

import static apigw.proxy.ProxyHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Connect-time SSRF rules for named upstreams, with a scripted resolver standing in for hostile DNS. */
class SsrfTest {

    private final Map<String, InetAddress[]> dns = new ConcurrentHashMap<>();
    private final AtomicInteger lookups = new AtomicInteger();
    private ProxyHarness h;
    private ProxyHarness.Echo echo;

    @BeforeEach
    void setUp() throws Exception {
        h = new ProxyHarness();
        h.lookup = host -> {
            lookups.incrementAndGet();
            InetAddress[] a = dns.get(host);
            if (a == null) {
                throw new UnknownHostException(host);
            }
            return a;
        };
        echo = h.upstream("echo", false);
        h.startGateway(new HttpServerOptions());
    }

    @AfterEach
    void tearDown() throws Exception {
        h.close();
    }

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    private void publish(String host, String allow) throws Exception {
        h.publish("{\"schemaVersion\":1,\"settings\":{\"upstreamAllowCidrs\":[" + allow + "]},"
                + "\"upstreams\":[{\"id\":\"u\",\"targets\":[{\"host\":\"" + host + "\",\"port\":" + echo.port + "}]}],"
                + "\"services\":[{\"id\":\"s\",\"upstreamId\":\"u\"}],"
                + "\"routes\":[{\"id\":\"r\",\"path\":\"/\",\"serviceId\":\"s\"}]}");
    }

    private record Resp(int status, String body) {
    }

    private Resp get() throws Exception {
        var client = h.vertx.createHttpClient(new HttpClientOptions());
        try {
            return await(client.request(new RequestOptions().setMethod(HttpMethod.GET).setHost("127.0.0.1")
                    .setPort(h.gatewayPort).setURI("/x")).compose(r -> r.send())
                    .compose(r -> r.body().map((Buffer b) -> new Resp(r.statusCode(), b.toString()))));
        } finally {
            client.close();
        }
    }

    @Test
    void nameResolvingToMetadataOrPrivateRangesIsNeverConnected() throws Exception {
        for (String internal : new String[] {"169.254.169.254", "10.1.2.3", "127.0.0.1", "::1", "fd00::1",
                "::ffff:127.0.0.1", "100.64.0.1", "0.0.0.0"}) {
            dns.put("svc.example", new InetAddress[] {ip(internal)});
            publish("svc.example", "");
            Resp r = get();
            assertThat(r.status()).as(internal).isEqualTo(502);
            assertThat(r.body()).doesNotContain("svc.example", internal, String.valueOf(echo.port));
            // Fresh resolver state per case: the next publish uses a new name.
            dns.remove("svc.example");
            h.close();
            setUp();
        }
        assertThat(echo.hits.get()).isZero();
    }

    @Test
    void everyResolvedAddressMustBePermitted() throws Exception {
        dns.put("mixed.example", new InetAddress[] {ip("127.0.0.1"), ip("169.254.169.254")});
        publish("mixed.example", "\"127.0.0.0/8\"");
        assertThat(get().status()).isEqualTo(502);
        assertThat(echo.hits.get()).isZero();
    }

    @Test
    void connectionIsPinnedToTheCheckedAddressAndHostIsKept() throws Exception {
        // "rebind.test" does not exist in real DNS: the request only succeeds because the gateway connects
        // to the address it checked, not to whatever a second lookup (at connect time) would return.
        dns.put("rebind.test", new InetAddress[] {ip("127.0.0.1")});
        publish("rebind.test", "\"127.0.0.0/8\"");
        Resp r = get();
        assertThat(r.status()).isEqualTo(200);
        assertThat(new JsonObject(r.body()).getString("authority")).isEqualTo("rebind.test:" + echo.port);
        // Within the cache TTL the name is not looked up again, so a DNS answer that flips to an internal
        // address cannot be raced in between check and connect.
        int before = lookups.get();
        dns.put("rebind.test", new InetAddress[] {ip("169.254.169.254")});
        assertThat(get().status()).isEqualTo(200);
        assertThat(lookups.get()).isEqualTo(before);
    }

    @Test
    void unknownHostIsAGeneric502() throws Exception {
        publish("nowhere.test", "");
        Resp r = get();
        assertThat(r.status()).isEqualTo(502);
        assertThat(r.body()).doesNotContain("nowhere.test");
    }
}
