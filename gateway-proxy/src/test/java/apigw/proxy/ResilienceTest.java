package apigw.proxy;

import static apigw.proxy.ProxyHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.RequestOptions;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Retries, retry budget, circuit breaker, passive ejection and active health checks, on real servers. */
class ResilienceTest {

    private ProxyHarness h;
    private ProxyHarness.Echo good;
    private ProxyHarness.Echo sick;
    private final AtomicInteger sickHealthStatus = new AtomicInteger(500);

    @BeforeEach
    void setUp() throws Exception {
        h = new ProxyHarness();
        good = h.upstream("good", false);
        sick = h.custom("sick", req -> {
            if (req.path().equals("/healthz")) {
                req.response().setStatusCode(sickHealthStatus.get()).end();
            } else {
                req.response().setStatusCode(503).end("sick");
            }
        });
        h.startGateway(new HttpServerOptions());
    }

    @AfterEach
    void tearDown() throws Exception {
        h.close();
    }

    private static String target(ProxyHarness.Echo e) {
        return "{\"host\":\"127.0.0.1\",\"port\":" + e.port + "}";
    }

    private void publish(String upstreamExtra, String targets, String routeExtra) throws Exception {
        h.publish("{\"schemaVersion\":1,\"settings\":{\"upstreamAllowCidrs\":[\"127.0.0.0/8\"]},"
                + "\"upstreams\":[{\"id\":\"u\",\"balancer\":\"round-robin\",\"targets\":[" + targets + "]"
                + upstreamExtra + "}],\"services\":[{\"id\":\"s\",\"upstreamId\":\"u\"}],"
                + "\"routes\":[{\"id\":\"r\",\"path\":\"/\",\"serviceId\":\"s\"" + routeExtra + "}]}");
    }

    private int call(HttpMethod m, String uri, String body) throws Exception {
        var c = h.vertx.createHttpClient(new HttpClientOptions());
        try {
            return await(c.request(new RequestOptions().setMethod(m).setHost("127.0.0.1").setPort(h.gatewayPort)
                    .setURI(uri)).compose(r -> body == null ? r.send() : r.send(Buffer.buffer(body)))
                    .compose(r -> r.body().map(b -> r.statusCode())));
        } finally {
            await(c.close());
        }
    }

    @Test
    void retriesIdempotentRequestsButNeverAStreamedBodyOrAPost() throws Exception {
        publish("", target(good), ",\"retries\":{\"attempts\":2,\"backoffMs\":5}");
        assertThat(call(HttpMethod.GET, "/flaky/2/a", null)).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/flaky/3/b", null)).isEqualTo(503);
        // POST is not idempotent: never retried.
        assertThat(call(HttpMethod.POST, "/flaky/1/c", "x")).isEqualTo(503);
        // PUT is idempotent, but its body already streamed to the upstream: not retried.
        assertThat(call(HttpMethod.PUT, "/flaky/1/d", "payload")).isEqualTo(503);
        assertThat(call(HttpMethod.PUT, "/flaky/1/e", null)).isEqualTo(200);
    }

    @Test
    void connectFailuresMoveToAnotherTargetEvenWithABody() throws Exception {
        publish("", "{\"host\":\"127.0.0.1\",\"port\":1}," + target(good),
                ",\"retries\":{\"attempts\":1,\"backoffMs\":0,\"methods\":[\"GET\",\"PUT\"]}");
        for (int i = 0; i < 6; i++) {
            assertThat(call(HttpMethod.PUT, "/x" + i, "body-" + i)).isEqualTo(200);
        }
        assertThat(good.hits.get()).isEqualTo(6);
        // Without retries, round robin sends half the requests to the dead target.
        publish("", "{\"host\":\"127.0.0.1\",\"port\":1}," + target(good), "");
        int failures = 0;
        for (int i = 0; i < 6; i++) {
            failures += call(HttpMethod.GET, "/y" + i, null) == 502 ? 1 : 0;
        }
        assertThat(failures).isEqualTo(3);
    }

    @Test
    void retryBudgetCapsRetryStorms() throws Exception {
        publish("", target(sick), ",\"retries\":{\"attempts\":3,\"backoffMs\":0,\"budgetPercent\":0,"
                + "\"minRetriesPerSecond\":1}");
        for (int i = 0; i < 30; i++) {
            assertThat(call(HttpMethod.GET, "/s" + i, null)).isEqualTo(503);
        }
        // 30 requests + at most 10 retries (1/s over the 10 s window), not 30 x 4.
        assertThat(sick.hits.get()).isBetween(30, 40);
    }

    @Test
    void breakerOpensFailsFastAndClosesAfterAHalfOpenTrial() throws Exception {
        AtomicInteger mode = new AtomicInteger(503);
        ProxyHarness.Echo flappy = h.custom("flappy", req -> req.response().setStatusCode(mode.get()).end());
        publish(",\"circuitBreaker\":{\"windowSeconds\":10,\"minCalls\":4,\"failureRatePercent\":50,"
                + "\"openMs\":400,\"halfOpenCalls\":1}", target(flappy), "");
        for (int i = 0; i < 4; i++) {
            assertThat(call(HttpMethod.GET, "/f", null)).isEqualTo(503);
        }
        int hits = flappy.hits.get();
        for (int i = 0; i < 5; i++) {
            assertThat(call(HttpMethod.GET, "/f", null)).isEqualTo(503);
        }
        assertThat(flappy.hits.get()).as("open breaker must not reach the upstream").isEqualTo(hits);
        mode.set(200);
        Thread.sleep(450);
        assertThat(call(HttpMethod.GET, "/f", null)).isEqualTo(200);
        for (int i = 0; i < 5; i++) {
            assertThat(call(HttpMethod.GET, "/f", null)).isEqualTo(200);
        }
        assertThat(flappy.hits.get()).isEqualTo(hits + 6);
    }

    @Test
    void slowCallsOpenTheBreaker() throws Exception {
        publish(",\"circuitBreaker\":{\"minCalls\":3,\"failureRatePercent\":100,\"slowCallMs\":100,"
                + "\"slowCallRatePercent\":50,\"openMs\":10000}", target(good), "");
        for (int i = 0; i < 3; i++) {
            assertThat(call(HttpMethod.GET, "/slow/150", null)).isEqualTo(200);
        }
        int hits = good.hits.get();
        assertThat(call(HttpMethod.GET, "/fast", null)).isEqualTo(503);
        assertThat(good.hits.get()).isEqualTo(hits);
    }

    @Test
    void passiveEjectionStopsSendingToAFailingTarget() throws Exception {
        publish(",\"healthCheck\":{\"passive\":{\"consecutiveFailures\":2,\"ejectionMs\":60000,"
                + "\"maxEjectionPercent\":50}}", target(sick) + "," + target(good), "");
        for (int i = 0; i < 20; i++) {
            call(HttpMethod.GET, "/p" + i, null);
        }
        assertThat(sick.hits.get()).isEqualTo(2);
        assertThat(good.hits.get()).isEqualTo(18);
    }

    @Test
    void activeChecksRemoveAndRestoreTargetsAndPanicKeepsTrafficFlowing() throws Exception {
        String active = ",\"healthCheck\":{\"active\":{\"path\":\"/healthz\",\"intervalMs\":500,\"timeoutMs\":200,"
                + "\"healthyThreshold\":1,\"unhealthyThreshold\":1}}";
        publish(active, target(sick) + "," + target(good), "");
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
            int before = sick.hits.get();
            for (int i = 0; i < 4; i++) {
                call(HttpMethod.GET, "/a", null);
            }
            // Probes also hit /healthz; only traffic matters, so compare excluding probes is not possible:
            // a short burst with no traffic to sick means it is out (probes add at most 1 per 500 ms).
            return sick.hits.get() - before <= 1;
        });
        sickHealthStatus.set(200);
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
            int before = sick.hits.get();
            for (int i = 0; i < 6; i++) {
                call(HttpMethod.GET, "/b", null);
            }
            return sick.hits.get() - before >= 3;
        });
        // Every target unhealthy (wrong health path): traffic still flows rather than failing closed.
        publish(active.replace("/healthz", "/nope"), target(good), "");
        Thread.sleep(1200);
        for (int i = 0; i < 5; i++) {
            assertThat(call(HttpMethod.GET, "/c", null)).isEqualTo(200);
        }
    }
}
