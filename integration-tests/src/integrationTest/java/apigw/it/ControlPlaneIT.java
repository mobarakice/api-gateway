package apigw.it;

import static apigw.it.EtcdGatewaySupport.await;
import static apigw.it.EtcdGatewaySupport.status;
import static org.assertj.core.api.Assertions.assertThat;

import apigw.config.etcd.EtcdClientConfig;
import apigw.config.etcd.EtcdHttpClient;
import apigw.config.etcd.Password;
import apigw.it.EtcdGatewaySupport.Gw;
import apigw.plugins.testing.TestJwt;
import apigw.testsupport.EtcdTestCluster;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.vertx.core.Vertx;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * M6 exit criteria against real processes: the control plane (JVM or native, same mode as the gateways)
 * over HTTPS with OIDC-style tokens, a 3-member etcd cluster with TLS + auth and least-privilege roles,
 * and 3 gateway replicas. A change published through the Admin API must be live on all 3 gateways
 * within 2 s; concurrent edits return 412; rollback restores the previous state.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ControlPlaneIT {

    private static final String PREFIX = "/gw/cp-it/";
    private static final String ISS = "https://idp.it.test";
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final long PROPAGATION_TARGET_MS = 2_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final KeyPair KEY = TestJwt.rsa();

    private static EtcdTestCluster etcd;
    private static HttpServer upstream;
    private static GatewayProcess cp;
    private static Map<String, String> cpEnv;
    private static final List<GatewayProcess> GATEWAYS = new ArrayList<>();
    private static final List<Gw> GW_ENVS = new ArrayList<>();
    private static HttpClient https;
    private static String base;
    private static Path auditFile;
    private static Path tmp;
    private static int cpMetricsPort;

    @BeforeAll
    static void start() throws Exception {
        tmp = Files.createTempDirectory("cp-it");
        etcd = new EtcdTestCluster(EtcdGatewaySupport.TLS, PREFIX, 3).start();
        upstream = EtcdGatewaySupport.echo();
        Path jwks = tmp.resolve("jwks.json");
        Files.writeString(jwks, TestJwt.jwks(TestJwt.jwk(KEY, "it-1", "RS256")));
        Path pw = tmp.resolve("cp-etcd-password");
        Files.writeString(pw, etcd.controlPlanePassword() + "\n");
        auditFile = tmp.resolve("audit.jsonl");
        int port = Ports.free();
        cpEnv = new HashMap<>(Map.of("CP_BIND_HOST", "127.0.0.1", "CP_HTTP_PORT", Integer.toString(port),
                "CP_TLS_CERT", EtcdGatewaySupport.TLS.resolve("server.pem").toString(),
                "CP_TLS_KEY", EtcdGatewaySupport.TLS.resolve("server-key.pem").toString(),
                "CP_ETCD_ENDPOINTS", etcd.endpoints(),
                "CP_ETCD_CA", EtcdGatewaySupport.TLS.resolve("ca.pem").toString(),
                "CP_ETCD_USER", EtcdTestCluster.CONTROL_PLANE_USER, "CP_ETCD_PASSWORD_FILE", pw.toString(),
                "CP_ETCD_PREFIX", PREFIX));
        cpMetricsPort = Ports.free();
        cpEnv.put("CP_METRICS_PORT", Integer.toString(cpMetricsPort));
        cpEnv.putAll(Map.of("CP_OIDC_ISSUER", ISS, "CP_OIDC_AUDIENCE", "admin-api", "CP_OIDC_ALGORITHMS", "RS256",
                "CP_OIDC_JWKS_FILE", jwks.toString(), "CP_AUDIT_FILE", auditFile.toString()));
        base = "https://127.0.0.1:" + port + "/admin/v1/";
        https = HttpClient.newBuilder().sslContext(trustTestCa()).connectTimeout(Duration.ofSeconds(3)).build();
        startControlPlane();
        seed();
        for (int i = 0; i < 3; i++) {
            Path lkg = tmp.resolve("lkg-" + i);
            Files.createDirectories(lkg);
            Gw g = EtcdGatewaySupport.gatewayEnv(etcd.endpoints(), PREFIX, etcd.gatewayPassword(), lkg, "gw-" + i);
            GW_ENVS.add(g);
            GATEWAYS.add(GatewayProcess.start(List.of(), g.env()));
        }
        for (int i = 0; i < 3; i++) {
            GATEWAYS.get(i).awaitLine(l -> l.contains("\"msg\":\"ready revision="), WAIT);
            await(GW_ENVS.get(i).http(), "/a", status(200), WAIT, GATEWAYS.get(i));
        }
    }

    @AfterAll
    static void stop() throws Exception {
        for (GatewayProcess g : GATEWAYS) {
            g.close();
        }
        if (cp != null) {
            cp.close();
        }
        if (upstream != null) {
            upstream.stop(0);
        }
        if (etcd != null) {
            etcd.close();
        }
    }

    private static void startControlPlane() throws Exception {
        cp = GatewayProcess.controlPlane(cpEnv);
        cp.awaitLine(l -> l.contains("control plane listening"), WAIT);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (call("GET", "ready", null, null).status() != 200) {
            assertThat(System.nanoTime()).as("control plane ready; output:\n" + String.join("\n", cp.output()))
                    .isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    private static SSLContext trustTestCa() throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        try (InputStream in = Files.newInputStream(EtcdGatewaySupport.TLS.resolve("ca.pem"))) {
            ks.setCertificateEntry("ca", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    static String token(String role, String aud) {
        long now = Instant.now().getEpochSecond();
        return TestJwt.sign(TestJwt.header("RS256", "it-1"), "{\"iss\":\"" + ISS + "\",\"sub\":\"" + role + "@it\","
                + "\"aud\":\"" + aud + "\",\"iat\":" + (now - 5) + ",\"exp\":" + (now + 600) + ",\"roles\":[\"" + role
                + "\"]}", "RS256", KEY.getPrivate());
    }

    static String as(String role) {
        return token(role, "admin-api");
    }

    record R(int status, JsonNode body, String etag, String raw) {
    }

    static R call(String method, String path, String token, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("authorization", "Bearer " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<String> r = https.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode n = r.body().isEmpty() ? null : JSON.readTree(r.body());
            return new R(r.statusCode(), n, r.headers().firstValue("etag").orElse(null), r.body());
        } catch (java.io.IOException e) {
            return new R(-1, null, null, e.toString());
        }
    }

    static String changeSet(String title) throws Exception {
        R r = call("POST", "changesets", as("editor"), "{\"title\":\"" + title + "\"}");
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.body().path("id").asText();
    }

    static void put(String cs, String path, String spec, String role, String... headers) throws Exception {
        R r = call("PUT", path + "?changeset=" + cs, as(role), "{\"spec\":" + spec + "}", headers);
        assertThat(r.status()).as(path + " " + r.raw()).isEqualTo(200);
    }

    static R publish(String cs) throws Exception {
        return call("POST", "changesets/" + cs + "/publish", as("publisher"), null);
    }

    private static void seed() throws Exception {
        String cs = changeSet("seed");
        put(cs, "settings", "{\"upstreamAllowCidrs\":[\"127.0.0.0/8\"]}", "admin");
        put(cs, "upstreams/echo", "{\"targets\":[{\"host\":\"127.0.0.1\",\"port\":" + upstream.getAddress().getPort()
                + "}]}", "editor");
        put(cs, "services/s", "{\"upstreamId\":\"echo\"}", "editor");
        put(cs, "routes/a", "{\"path\":\"/a\",\"serviceId\":\"s\"}", "editor");
        R p = publish(cs);
        assertThat(p.status()).as(p.raw()).isEqualTo(200);
    }

    /** Milliseconds from {@code t0} until every gateway answers {@code path} with {@code code}. */
    private static long untilAllGateways(long t0, String path, int code) throws Exception {
        List<CompletableFuture<Long>> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int port = GW_ENVS.get(i).http();
            GatewayProcess gw = GATEWAYS.get(i);
            all.add(CompletableFuture.supplyAsync(() -> {
                try {
                    await(port, path, status(code), WAIT, gw);
                    return (System.nanoTime() - t0) / 1_000_000;
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        long max = 0;
        for (CompletableFuture<Long> f : all) {
            max = Math.max(max, f.get(WAIT.toSeconds() + 5, TimeUnit.SECONDS));
        }
        return max;
    }

    @Test
    @Order(1)
    void publishedChangesReachAllThreeGatewaysWithinTarget() throws Exception {
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String cs = changeSet("propagation " + i);
            put(cs, "routes/p" + i, "{\"path\":\"/p" + i + "\",\"serviceId\":\"s\"}", "editor");
            long t0 = System.nanoTime();
            R p = publish(cs);
            assertThat(p.status()).as(p.raw()).isEqualTo(200);
            // Measured from sending the publish request to the last of 3 gateways serving the route.
            samples.add(untilAllGateways(t0, "/p" + i, 200));
        }
        System.out.println("CONFIG-PROPAGATION mode=" + GatewayProcess.mode() + " replicas=3 publish-to-all-ms="
                + samples);
        assertThat(samples).allSatisfy(ms -> assertThat(ms).isLessThanOrEqualTo(PROPAGATION_TARGET_MS));
        // Every gateway reports the latest revision in its heartbeat.
        long deadline = System.nanoTime() + WAIT.toNanos();
        R gws;
        do {
            gws = call("GET", "gateways", as("viewer"), null);
            Thread.sleep(200);
        } while ((gws.body().path("items").size() < 3 || gws.raw().contains("\"behind\":true"))
                && System.nanoTime() < deadline);
        assertThat(gws.body().path("items")).hasSize(3);
        assertThat(gws.raw()).doesNotContain("\"behind\":true");
        // Metrics: each gateway measured publish-to-serve and is not behind the newest publish it saw.
        HttpClient plain = HttpClient.newHttpClient();
        for (Gw g : GW_ENVS) {
            String text = plain.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + g.status() + "/metrics"))
                    .build(), HttpResponse.BodyHandlers.ofString()).body();
            Map<String, Double> m = metrics(text);
            assertThat(m.getOrDefault("gw_config_propagation_seconds_count", 0.0)).as(text).isGreaterThanOrEqualTo(1);
            assertThat(m.get("gw_config_published_revision")).isPositive()
                    .isLessThanOrEqualTo(m.get("gw_config_applied_revision"));
            assertThat(m.get("gw_config_state{state=\"SERVING\"}")).isEqualTo(1.0);
            assertThat(m.get("gw_etcd_request_seconds_count{op=\"put\"}")).isPositive();
            System.out.printf("CONFIG-PROPAGATION-METRIC mode=%s gateway-port=%d count=%.0f mean-ms=%.1f%n",
                    GatewayProcess.mode(), g.status(), m.get("gw_config_propagation_seconds_count"),
                    1000 * m.get("gw_config_propagation_seconds_sum") / m.get("gw_config_propagation_seconds_count"));
        }
        Map<String, Double> cpm = metrics(plain.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + cpMetricsPort + "/metrics")).build(),
                HttpResponse.BodyHandlers.ofString()).body());
        assertThat(cpm.get("cp_http_requests_total{endpoint=\"changesets.publish\",method=\"POST\",code=\"2xx\"}"))
                .isGreaterThanOrEqualTo(5);
        assertThat(cpm.get("cp_etcd_request_seconds_count{op=\"txn\"}")).isPositive();
    }

    /** Series name with labels -> value, from Prometheus text. */
    private static Map<String, Double> metrics(String text) {
        Map<String, Double> out = new HashMap<>();
        for (String line : text.split("\n")) {
            if (!line.startsWith("#") && !line.isBlank()) {
                int sp = line.lastIndexOf(' ');
                out.put(line.substring(0, sp), Double.parseDouble(line.substring(sp + 1)));
            }
        }
        return out;
    }

    @Test
    @Order(2)
    void concurrentEditsReturn412() throws Exception {
        R route = call("GET", "routes/a", as("viewer"), null);
        String etag = route.etag();
        String one = changeSet("edit one");
        String two = changeSet("edit two");
        put(one, "routes/a", "{\"path\":\"/a\",\"serviceId\":\"s\",\"methods\":[\"GET\"]}", "editor", "if-match", etag);
        put(two, "routes/a", "{\"path\":\"/a\",\"serviceId\":\"s\",\"methods\":[\"POST\"]}", "editor", "if-match",
                etag);
        assertThat(publish(one).status()).isEqualTo(200);
        R lost = publish(two);
        assertThat(lost.status()).as(lost.raw()).isEqualTo(412);
        assertThat(lost.body().path("conflicts").get(0).path("key").asText()).isEqualTo("routes/a");
        // Staging against the old ETag now fails immediately too.
        R stale = call("PUT", "routes/a?changeset=" + two, as("editor"),
                "{\"spec\":{\"path\":\"/a\",\"serviceId\":\"s\"}}", "if-match", etag);
        assertThat(stale.status()).isEqualTo(412);
        assertThat(call("GET", "routes/a", as("viewer"), null).body().path("spec").path("methods").get(0).asText())
                .isEqualTo("GET");
    }

    @Test
    @Order(3)
    void rollbackRestoresThePriorStateOnEveryGateway() throws Exception {
        String v1 = changeSet("v1");
        put(v1, "routes/rb", "{\"path\":\"/v1\",\"serviceId\":\"s\"}", "editor");
        assertThat(publish(v1).status()).isEqualTo(200);
        untilAllGateways(System.nanoTime(), "/v1", 200);
        String etag = call("GET", "routes/rb", as("viewer"), null).etag();
        String v2 = changeSet("v2");
        put(v2, "routes/rb", "{\"path\":\"/v2\",\"serviceId\":\"s\"}", "editor", "if-match", etag);
        R p2 = publish(v2);
        assertThat(p2.status()).isEqualTo(200);
        untilAllGateways(System.nanoTime(), "/v2", 200);
        untilAllGateways(System.nanoTime(), "/v1", 404);
        R rb = call("POST", "history/" + p2.body().path("seq").asLong() + "/rollback", as("publisher"), null);
        assertThat(rb.status()).as(rb.raw()).isEqualTo(200);
        long ms = untilAllGateways(System.nanoTime(), "/v1", 200);
        untilAllGateways(System.nanoTime(), "/v2", 404);
        System.out.println("ROLLBACK-PROPAGATION mode=" + GatewayProcess.mode() + " ms=" + ms);
        R entry = call("GET", "history/" + rb.body().path("seq").asLong(), as("viewer"), null);
        assertThat(entry.body().path("rollbackOf").asLong()).isEqualTo(p2.body().path("seq").asLong());
    }

    @Test
    @Order(4)
    void apiKeyIssuedThroughTheApiWorksOnTheGatewaysAndIsNeverStoredOrLogged() throws Exception {
        String cs = changeSet("keys");
        put(cs, "consumers/acme", "{\"username\":\"acme\"}", "editor");
        put(cs, "routes/secure", "{\"path\":\"/secure\",\"serviceId\":\"s\",\"plugins\":[{\"name\":\"key-auth\"},"
                + "{\"name\":\"rate-limit\",\"config\":{\"limit\":1000,\"key\":[\"consumer\"]}},"
                + "{\"name\":\"cache\",\"config\":{\"ttlSeconds\":1,\"cacheAuthenticated\":true}}]}",
                "editor");
        R key = call("POST", "consumers/acme/credentials?changeset=" + cs, as("editor"), "{\"type\":\"api-key\"}");
        assertThat(key.status()).as(key.raw()).isEqualTo(201);
        String apiKey = key.body().path("apiKey").asText();
        assertThat(publish(cs).status()).isEqualTo(200);
        untilAllGateways(System.nanoTime(), "/secure", 401);
        for (Gw g : GW_ENVS) {
            HttpResponse<String> ok = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + g.http() + "/secure")).header("x-api-key", apiKey).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(ok.statusCode()).isEqualTo(200);
        }
        String secret = apiKey.substring(apiKey.indexOf('.') + 1);
        assertThat(etcd.rootCtl("get", "--prefix", PREFIX)).doesNotContain(secret);
        assertThat(String.join("\n", cp.output())).doesNotContain(secret);
        assertThat(Files.readString(auditFile)).doesNotContain(secret);
        for (String path : List.of("consumers/acme/credentials", "history", "audit")) {
            R r = call("GET", path, as("admin"), null);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.raw()).doesNotContain(secret).doesNotContain("sha256$");
        }
    }

    @Test
    @Order(5)
    void privilegeEscalationIsRefusedAndAudited() throws Exception {
        String cs = changeSet("escalation");
        // Viewers read only; editors cannot publish, roll back, change settings or read audit.
        assertThat(call("PUT", "routes/x?changeset=" + cs, as("viewer"), "{\"spec\":{\"path\":\"/x\",\"serviceId\":"
                + "\"s\"}}").status()).isEqualTo(403);
        put(cs, "routes/x", "{\"path\":\"/x\",\"serviceId\":\"s\"}", "editor");
        assertThat(call("POST", "changesets/" + cs + "/publish", as("editor"), null).status()).isEqualTo(403);
        assertThat(call("POST", "history/1/rollback", as("editor"), null).status()).isEqualTo(403);
        assertThat(call("PUT", "settings?changeset=" + cs, as("publisher"), "{\"spec\":{}}").status()).isEqualTo(403);
        assertThat(call("GET", "audit", as("publisher"), null).status()).isEqualTo(403);
        // Tokens for another audience, unsigned tokens and no tokens.
        assertThat(call("GET", "routes", token("admin", "other-api"), null).status()).isEqualTo(401);
        String none = TestJwt.b64("{\"alg\":\"none\"}") + "." + TestJwt.b64("{\"iss\":\"" + ISS + "\",\"sub\":\"x\","
                + "\"aud\":\"admin-api\",\"exp\":9999999999,\"roles\":[\"admin\"]}") + ".";
        assertThat(call("GET", "routes", none, null).status()).isEqualTo(401);
        assertThat(call("GET", "routes", null, null).status()).isEqualTo(401);
        // Refused attempts are in the audit sink with the caller's identity; the publish audit is in etcd.
        String sink = Files.readString(auditFile);
        assertThat(sink).contains("\"outcome\":\"denied\"").contains("\"subject\":\"viewer@it\"")
                .contains("\"subject\":\"editor@it\"");
        assertThat(call("GET", "audit", as("admin"), null).raw()).contains("\"action\":\"publish\"")
                .contains("\"subject\":\"publisher@it\"");
        assertThat(call("DELETE", "changesets/" + cs, as("editor"), null).status()).isEqualTo(204);
    }

    @Test
    @Order(6)
    void etcdRolesAreLeastPrivilege() throws Exception {
        Vertx vertx = Vertx.vertx();
        try (EtcdHttpClient cpClient = client(vertx, EtcdTestCluster.CONTROL_PLANE_USER, etcd.controlPlanePassword());
                EtcdHttpClient gwClient = client(vertx, EtcdTestCluster.GATEWAY_USER, etcd.gatewayPassword())) {
            assertThat(get(cpClient.canWrite(PREFIX + "routes/x"))).isTrue();
            assertThat(get(cpClient.canWrite(PREFIX + "history/head"))).isTrue();
            // The control plane cannot forge gateway heartbeats; gateways cannot write config or history.
            assertThat(get(cpClient.canWrite(PREFIX + "nodes/gw-0"))).isFalse();
            assertThat(get(gwClient.canWrite(PREFIX + "routes/x"))).isFalse();
            assertThat(get(gwClient.canWrite(PREFIX + "changesets/x"))).isFalse();
            assertThat(get(gwClient.canWrite(PREFIX + "audit/x"))).isFalse();
        } finally {
            vertx.close();
        }
    }

    private static EtcdHttpClient client(Vertx vertx, String user, String password) {
        return EtcdHttpClient.create(vertx, new EtcdClientConfig(List.of(URI.create(etcd.endpoints().split(",")[0])),
                Optional.of(EtcdGatewaySupport.TLS.resolve("ca.pem")), Optional.empty(), Optional.empty(),
                Optional.of(user), Optional.of(Password.of(password)), Duration.ofSeconds(5)));
    }

    private static <T> T get(io.vertx.core.Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @Order(7)
    void gatewaysKeepServingWhileTheControlPlaneIsDownAndItRecovers() throws Exception {
        long t0 = System.nanoTime();
        // 143 = terminated by SIGTERM after the shutdown hook ran (same as the gateway).
        assertThat(cp.terminate(Duration.ofSeconds(15))).isEqualTo(143);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(8_000);
        assertThat(cp.output()).anySatisfy(l -> assertThat(l).contains("control plane stopped"));
        for (Gw g : GW_ENVS) {
            assertThat(EtcdGatewaySupport.get(g.http(), "/a").status()).isEqualTo(200);
        }
        startControlPlane();
        String cs = changeSet("after restart");
        put(cs, "routes/back", "{\"path\":\"/back\",\"serviceId\":\"s\"}", "editor");
        assertThat(publish(cs).status()).isEqualTo(200);
        untilAllGateways(System.nanoTime(), "/back", 200);
    }
}
