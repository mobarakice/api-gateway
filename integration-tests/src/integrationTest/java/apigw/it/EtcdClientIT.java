package apigw.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import apigw.config.etcd.EtcdClientConfig;
import apigw.config.etcd.EtcdException;
import apigw.config.etcd.EtcdHttpClient;
import apigw.config.etcd.Password;
import apigw.config.etcd.RangeResult;
import apigw.config.etcd.WatchEvent;
import apigw.config.etcd.WatchResponse;
import apigw.testsupport.EtcdTestNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The etcd client against a real etcd (official image) with TLS and authentication on. No mocks. */
class EtcdClientIT {

    private static final String PREFIX = "/gw/it/";
    private static final Path TLS = Path.of(System.getProperty("gw.test.tls.dir"));
    private static EtcdTestNode etcd;
    private static Vertx vertx;

    @BeforeAll
    static void start() {
        etcd = new EtcdTestNode(TLS, PREFIX).start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() throws Exception {
        await(vertx.close());
        etcd.close();
    }

    private static EtcdHttpClient gatewayClient(String password) {
        return EtcdHttpClient.create(vertx, new EtcdClientConfig(List.of(URI.create(etcd.endpoint())),
                Optional.of(TLS.resolve("ca.pem")), Optional.empty(), Optional.empty(),
                Optional.of(EtcdTestNode.GATEWAY_USER), Optional.of(Password.of(password)), Duration.ofSeconds(5)));
    }

    @Test
    void readsUnderTlsWithAuth() throws Exception {
        etcd.rootCtl("put", PREFIX + "routes/r1", "{\"id\":\"r1\"}");
        try (EtcdHttpClient client = gatewayClient(etcd.gatewayPassword())) {
            RangeResult r = await(client.rangePrefix(PREFIX + "routes/", PREFIX + "routes/", 0, 0));
            assertThat(r.kvs()).anySatisfy(kv -> {
                assertThat(kv.key()).isEqualTo(PREFIX + "routes/r1");
                assertThat(kv.valueUtf8()).isEqualTo("{\"id\":\"r1\"}");
            });
            RangeResult count = await(client.countPrefix(PREFIX));
            assertThat(count.count()).isGreaterThanOrEqualTo(1);
            assertThat(count.kvs()).isEmpty();
        }
    }

    @Test
    void gatewayRoleCannotWriteConfigButCanWriteItsNodeKey() throws Exception {
        try (EtcdHttpClient client = gatewayClient(etcd.gatewayPassword())) {
            assertThat(await(client.canWrite(PREFIX + "routes/x"))).isFalse();
            assertThatThrownBy(() -> await(client.put(PREFIX + "routes/x", bytes("{}"), 0)))
                    .hasCauseInstanceOf(EtcdException.class)
                    .cause().satisfies(e -> assertThat(((EtcdException) e).permissionDenied()).isTrue());
            // Nothing was written by the probe or the denied put.
            assertThat(etcd.rootCtl("get", PREFIX + "routes/x")).isBlank();

            assertThat(await(client.canWrite(PREFIX + "nodes/gw-1"))).isTrue();
            assertThat(await(client.put(PREFIX + "nodes/gw-1", bytes("{\"appliedRevision\":1}"), 0))).isPositive();
            // canWrite never writes, even when permitted.
            assertThat(etcd.rootCtl("get", "--print-value-only", PREFIX + "nodes/gw-1").trim())
                    .isEqualTo("{\"appliedRevision\":1}");
        }
    }

    @Test
    void gatewayRoleCannotReadOutsideItsPrefix() throws Exception {
        etcd.rootCtl("put", "/other/secret", "x");
        try (EtcdHttpClient client = gatewayClient(etcd.gatewayPassword())) {
            assertThatThrownBy(() -> await(client.get("/other/secret", 0)))
                    .cause().satisfies(e -> assertThat(((EtcdException) e).permissionDenied()).isTrue());
        }
    }

    @Test
    void wrongPasswordIsRejected() {
        try (EtcdHttpClient client = gatewayClient("not-the-password")) {
            assertThatThrownBy(() -> await(client.authenticate()))
                    .cause().isInstanceOf(EtcdException.class)
                    .hasMessageContaining("authentication failed")
                    .hasMessageNotContaining("not-the-password");
        }
    }

    @Test
    void watchDeliversEventsFromTheRequestedRevision() throws Exception {
        try (EtcdHttpClient client = gatewayClient(etcd.gatewayPassword())) {
            long rev = await(client.countPrefix(PREFIX + "services/")).revision();
            List<WatchResponse> seen = new CopyOnWriteArrayList<>();
            List<Throwable> ended = new CopyOnWriteArrayList<>();
            EtcdHttpClient.Watch watch = client.watchPrefix(PREFIX + "services/", rev + 1, true, seen::add,
                    ended::add);
            Awaitility.await().atMost(Duration.ofSeconds(10))
                    .until(() -> seen.stream().anyMatch(WatchResponse::created));

            etcd.rootCtl("put", PREFIX + "services/s1", "v1");
            etcd.rootCtl("del", PREFIX + "services/s1");
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> events(seen).size() >= 2);
            assertThat(events(seen)).extracting(WatchEvent::type)
                    .containsExactly(WatchEvent.Type.PUT, WatchEvent.Type.DELETE);
            assertThat(events(seen).getFirst().kv().valueUtf8()).isEqualTo("v1");

            watch.cancel();
            await(watch.ended());
            assertThat(ended).hasSize(1);
        }
    }

    @Test
    void watchFromACompactedRevisionIsCanceledWithCompactRevision() throws Exception {
        try (EtcdHttpClient client = gatewayClient(etcd.gatewayPassword())) {
            etcd.rootCtl("put", PREFIX + "upstreams/u1", "a");
            long old = await(client.countPrefix(PREFIX)).revision();
            etcd.rootCtl("put", PREFIX + "upstreams/u1", "b");
            long now = await(client.countPrefix(PREFIX)).revision();
            etcd.rootCtl("compaction", Long.toString(now));

            List<WatchResponse> seen = new CopyOnWriteArrayList<>();
            List<Throwable> ended = new CopyOnWriteArrayList<>();
            client.watchPrefix(PREFIX, old, false, seen::add, ended::add);
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !ended.isEmpty());
            assertThat(seen).anySatisfy(r -> {
                assertThat(r.canceled()).isTrue();
                assertThat(r.compactRevision()).isEqualTo(now);
            });
            assertThat(ended.getFirst()).isInstanceOf(EtcdException.class);
            assertThat(((EtcdException) ended.getFirst()).code()).isEqualTo(EtcdException.OUT_OF_RANGE);
        }
    }

    @Test
    void unreachableEndpointFailsWithTransportError() {
        EtcdHttpClient client = EtcdHttpClient.create(vertx, new EtcdClientConfig(
                List.of(URI.create("https://127.0.0.1:" + Ports.free())), Optional.of(TLS.resolve("ca.pem")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Duration.ofSeconds(2)));
        try (client) {
            assertThatThrownBy(() -> await(client.countPrefix(PREFIX)))
                    .cause().satisfies(e -> assertThat(((EtcdException) e).code()).isEqualTo(EtcdException.TRANSPORT));
        }
    }

    private static List<WatchEvent> events(List<WatchResponse> responses) {
        return responses.stream().flatMap(r -> r.events().stream()).toList();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static <T> T await(Future<T> f) throws InterruptedException, ExecutionException,
            java.util.concurrent.TimeoutException {
        return f.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }
}
