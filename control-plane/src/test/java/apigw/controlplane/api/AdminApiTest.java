package apigw.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import apigw.controlplane.Json;
import apigw.controlplane.audit.AuditLog;
import apigw.controlplane.auth.AdminAuth;
import apigw.controlplane.changes.ChangeSets;
import apigw.controlplane.changes.DryRun;
import apigw.controlplane.changes.Limits;
import apigw.controlplane.changes.Publisher;
import apigw.controlplane.store.MemoryKvStore;
import apigw.model.ApiKeyHash;
import apigw.plugins.BuiltinPlugins;
import apigw.plugins.jwt.JwtAlgorithm;
import apigw.plugins.jwt.Jwks;
import apigw.plugins.jwt.KeySet;
import apigw.plugins.testing.TestJwt;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The Admin API over real HTTP with real signed tokens; etcd is the in-memory stand-in. */
class AdminApiTest {

    private static final String PREFIX = "/gw/api/";
    private static final String ISS = "https://idp.test";
    private static final KeyPair KEY = TestJwt.rsa();
    private static final KeyPair OTHER = TestJwt.rsa();
    private static Vertx vertx;
    private static HttpServer server;
    private static MemoryKvStore kv;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        vertx = Vertx.vertx();
        kv = new MemoryKvStore();
        kv.put(PREFIX + "meta/schema-version", "1");
        Clock clock = Clock.systemUTC();
        AuditLog audit = new AuditLog(PREFIX, kv, clock, Optional.empty(), 1000);
        ChangeSets cs = new ChangeSets(PREFIX, kv, audit, clock, Limits.DEFAULTS);
        Publisher pub = new Publisher(PREFIX, kv, cs, audit, new DryRun(vertx, clock), clock, Limits.DEFAULTS);
        KeySet keys = Jwks.parse(Json.parse(TestJwt.jwks(TestJwt.jwk(KEY, "k1", "RS256"))
                .getBytes(StandardCharsets.UTF_8))).keys();
        AdminAuth auth = new AdminAuth(new AdminAuth.Settings(ISS, Set.of("admin-api"), EnumSet.of(JwtAlgorithm.RS256),
                "roles", "", 30), () -> keys, clock);
        AdminApi api = new AdminApi(PREFIX, kv, auth, cs, pub, audit, BuiltinPlugins.registry(), clock, List.of(),
                () -> Future.succeededFuture(true));
        server = vertx.createHttpServer().requestHandler(api).listen(0, "127.0.0.1").toCompletionStage()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @AfterAll
    static void stop() {
        vertx.close();
    }

    static String token(String sub, String roles, String aud, long expOffset, Object key, String alg, String kid) {
        long now = Instant.now().getEpochSecond();
        String claims = "{\"iss\":\"" + ISS + "\",\"sub\":\"" + sub + "\",\"aud\":\"" + aud + "\",\"iat\":" + (now - 5)
                + ",\"exp\":" + (now + expOffset) + ",\"roles\":" + roles + "}";
        return TestJwt.sign(TestJwt.header(alg, kid), claims, alg, key);
    }

    static String as(String role) {
        return token(role + "-user", "[\"" + role + "\"]", "admin-api", 300, KEY.getPrivate(), "RS256", "k1");
    }

    record R(int status, JsonNode body, HttpResponse<String> raw) {
        String etag() {
            return raw.headers().firstValue("etag").orElse(null);
        }
    }

    static R call(String method, String path, String token, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.actualPort() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("authorization", "Bearer " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode n = r.body().isEmpty() ? null : Json.parse(r.body().getBytes(StandardCharsets.UTF_8));
        return new R(r.statusCode(), n, r);
    }

    static String newChangeSet(String title) throws Exception {
        R r = call("POST", "/admin/v1/changesets", as("editor"), "{\"title\":\"" + title + "\"}");
        assertThat(r.status()).isEqualTo(201);
        return r.body().path("id").asText();
    }

    static void seed() throws Exception {
        if (kv.value(PREFIX + "routes/a").isPresent()) {
            return;
        }
        String admin = as("admin");
        String cs = newChangeSet("seed");
        assertThat(call("PUT", "/admin/v1/settings?changeset=" + cs, admin,
                "{\"spec\":{\"upstreamAllowCidrs\":[\"127.0.0.0/8\"]}}").status()).isEqualTo(200);
        assertThat(call("PUT", "/admin/v1/upstreams/u?changeset=" + cs, admin,
                "{\"spec\":{\"targets\":[{\"host\":\"127.0.0.1\",\"port\":9}]}}").status()).isEqualTo(200);
        assertThat(call("PUT", "/admin/v1/services/s?changeset=" + cs, admin,
                "{\"spec\":{\"upstreamId\":\"u\"}}").status()).isEqualTo(200);
        assertThat(call("PUT", "/admin/v1/routes/a?changeset=" + cs, admin,
                "{\"spec\":{\"path\":\"/a\",\"serviceId\":\"s\"}}").status()).isEqualTo(200);
        R p = call("POST", "/admin/v1/changesets/" + cs + "/publish", admin, null);
        assertThat(p.status()).as(String.valueOf(p.body())).isEqualTo(200);
    }

    @Test
    void healthAndReadyNeedNoTokenEverythingElseDoes() throws Exception {
        assertThat(call("GET", "/admin/v1/health", null, null).status()).isEqualTo(200);
        assertThat(call("GET", "/admin/v1/ready", null, null).status()).isEqualTo(200);
        R r = call("GET", "/admin/v1/routes", null, null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.raw().headers().firstValue("content-type")).hasValue("application/problem+json");
        assertThat(r.raw().headers().firstValue("www-authenticate")).isPresent();
        assertThat(r.body().path("requestId").asText()).hasSize(16);
    }

    @Test
    void badTokensAreRejected() throws Exception {
        String wrongAud = token("x", "[\"admin\"]", "other", 300, KEY.getPrivate(), "RS256", "k1");
        String expired = token("x", "[\"admin\"]", "admin-api", -3600, KEY.getPrivate(), "RS256", "k1");
        String wrongKey = token("x", "[\"admin\"]", "admin-api", 300, OTHER.getPrivate(), "RS256", "k1");
        // Algorithm confusion: an HMAC token "signed" with the public key's bytes.
        String confused = token("x", "[\"admin\"]", "admin-api", 300, KEY.getPublic().getEncoded(), "HS256", "k1");
        String none = TestJwt.b64("{\"alg\":\"none\"}") + "." + TestJwt.b64("{\"iss\":\"" + ISS
                + "\",\"sub\":\"x\",\"aud\":\"admin-api\",\"exp\":9999999999,\"roles\":[\"admin\"]}") + ".";
        for (String t : List.of(wrongAud, expired, wrongKey, confused, none, "garbage")) {
            assertThat(call("GET", "/admin/v1/me", t, null).status()).as(t).isEqualTo(401);
        }
        String noRole = token("x", "[\"superuser\"]", "admin-api", 300, KEY.getPrivate(), "RS256", "k1");
        assertThat(call("GET", "/admin/v1/me", noRole, null).status()).isEqualTo(403);
    }

    @Test
    void rolesAreEnforcedServerSide() throws Exception {
        seed();
        R me = call("GET", "/admin/v1/me", as("viewer"), null);
        assertThat(me.body().path("role").asText()).isEqualTo("viewer");
        assertThat(call("GET", "/admin/v1/routes", as("viewer"), null).status()).isEqualTo(200);
        assertThat(call("POST", "/admin/v1/changesets", as("viewer"), "{\"title\":\"t\"}").status()).isEqualTo(403);
        String cs = newChangeSet("roles");
        assertThat(call("PUT", "/admin/v1/routes/z?changeset=" + cs, as("viewer"),
                "{\"spec\":{\"path\":\"/z\",\"serviceId\":\"s\"}}").status()).isEqualTo(403);
        assertThat(call("PUT", "/admin/v1/routes/z?changeset=" + cs, as("editor"),
                "{\"spec\":{\"path\":\"/z\",\"serviceId\":\"s\"}}").status()).isEqualTo(200);
        // Editors stage, publishers publish; settings and audit are admin-only.
        assertThat(call("POST", "/admin/v1/changesets/" + cs + "/publish", as("editor"), null).status()).isEqualTo(403);
        assertThat(call("PUT", "/admin/v1/settings?changeset=" + cs, as("publisher"), "{\"spec\":{}}").status())
                .isEqualTo(403);
        assertThat(call("GET", "/admin/v1/audit", as("publisher"), null).status()).isEqualTo(403);
        assertThat(call("POST", "/admin/v1/changesets/" + cs + "/publish", as("publisher"), null).status())
                .isEqualTo(200);
        R audit = call("GET", "/admin/v1/audit", as("admin"), null);
        assertThat(audit.status()).isEqualTo(200);
        // Successful mutations are in the etcd window; refused attempts go to the log and sink only
        // (so unauthorised callers cannot fill etcd, ADR 0021).
        assertThat(audit.body().toString()).contains("\"action\":\"publish\"")
                .contains("\"subject\":\"publisher-user\"").doesNotContain("\"outcome\":\"denied\"");
    }

    @Test
    void etagsAndIfMatchGuardConcurrentEdits() throws Exception {
        seed();
        R route = call("GET", "/admin/v1/routes/a", as("viewer"), null);
        assertThat(route.etag()).isEqualTo("\"" + route.body().path("revision").asLong() + "\"");
        String cs = newChangeSet("etag");
        String body = "{\"spec\":{\"path\":\"/a2\",\"serviceId\":\"s\"}}";
        assertThat(call("PUT", "/admin/v1/routes/a?changeset=" + cs, as("editor"), body).status()).isEqualTo(428);
        R stale = call("PUT", "/admin/v1/routes/a?changeset=" + cs, as("editor"), body, "if-match", "\"1\"");
        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.body().path("conflicts").get(0).path("key").asText()).isEqualTo("routes/a");
        assertThat(call("PUT", "/admin/v1/routes/a?changeset=" + cs, as("editor"), body, "if-match", route.etag())
                .status()).isEqualTo(200);
        R diff = call("GET", "/admin/v1/changesets/" + cs + "/diff", as("viewer"), null);
        assertThat(diff.body().path("items").get(0).path("before").path("spec").path("path").asText()).isEqualTo("/a");
        assertThat(diff.body().path("items").get(0).path("after").path("spec").path("path").asText()).isEqualTo("/a2");
        R valid = call("POST", "/admin/v1/changesets/" + cs + "/validate", as("editor"), null);
        assertThat(valid.body().path("valid").asBoolean()).isTrue();
        // Discard to leave route a as it was for the other tests.
        assertThat(call("DELETE", "/admin/v1/changesets/" + cs, as("editor"), null).status()).isEqualTo(204);
    }

    @Test
    void validationErrorsNameTheFieldAndRule() throws Exception {
        String cs = newChangeSet("invalid");
        R r = call("PUT", "/admin/v1/routes/bad?changeset=" + cs, as("editor"),
                "{\"spec\":{\"path\":\"no-slash\",\"serviceId\":\"s\",\"bogus\":1}}");
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body().path("errors").toString()).contains("routes/bad.spec");
        R key = call("PUT", "/admin/v1/certificates/c?changeset=" + cs, as("admin"),
                "{\"spec\":{\"certRef\":\"-----BEGIN PRIVATE KEY-----abc\",\"keyRef\":\"x\"}}");
        assertThat(key.status()).isEqualTo(422);
        assertThat(key.body().toString()).contains("private key").doesNotContain("abc");
        assertThat(call("PUT", "/admin/v1/routes/bad?changeset=" + cs, as("editor"), "{not json").status())
                .isEqualTo(400);
        assertThat(call("PUT", "/admin/v1/routes/bad", as("editor"), "{\"spec\":{}}").status()).isEqualTo(400);
        assertThat(call("PUT", "/admin/v1/routes/bad?changeset=" + cs, as("editor"), "x".repeat(2 * 1024 * 1024))
                .status()).isEqualTo(413);
    }

    @Test
    void apiKeysAreShownOnceAndNeverReturnedAgain() throws Exception {
        seed();
        String cs = newChangeSet("keys");
        assertThat(call("PUT", "/admin/v1/consumers/c1?changeset=" + cs, as("editor"), "{\"spec\":{}}").status())
                .isEqualTo(200);
        assertThat(call("POST", "/admin/v1/consumers/c1/credentials?changeset=" + cs, as("editor"),
                "{\"type\":\"api-key\",\"hash\":\"sha256$x$y\"}").status()).isEqualTo(422);
        R created = call("POST", "/admin/v1/consumers/c1/credentials?changeset=" + cs, as("editor"),
                "{\"type\":\"api-key\"}");
        assertThat(created.status()).isEqualTo(201);
        String apiKey = created.body().path("apiKey").asText();
        String secret = apiKey.substring(apiKey.indexOf('.') + 1);
        assertThat(apiKey).startsWith(created.body().path("keyId").asText() + ".");
        assertThat(call("POST", "/admin/v1/changesets/" + cs + "/publish", as("publisher"), null).status())
                .isEqualTo(200);
        String id = created.body().path("id").asText();
        String stored = kv.value(PREFIX + "credentials/c1/" + id).orElseThrow();
        assertThat(stored).doesNotContain(secret);
        String hash = Json.parse(stored.getBytes(StandardCharsets.UTF_8)).path("spec").path("hash").asText();
        assertThat(ApiKeyHash.matches(ApiKeyHash.parse(hash), secret)).isTrue();
        for (String path : List.of("/admin/v1/consumers/c1/credentials", "/admin/v1/consumers/c1/credentials/" + id,
                "/admin/v1/history", "/admin/v1/audit")) {
            R r = call("GET", path, as("admin"), null);
            assertThat(r.status()).as(path).isEqualTo(200);
            assertThat(r.raw().body()).as(path).doesNotContain(secret).doesNotContain(hash);
        }
        assertThat(kv.keys(PREFIX)).allSatisfy(k -> assertThat(kv.value(k).orElse("")).doesNotContain(secret));
    }

    @Test
    void catalogServesEveryPluginSchema() throws Exception {
        R r = call("GET", "/admin/v1/plugins", as("viewer"), null);
        assertThat(r.body().path("items")).hasSize(BuiltinPlugins.registry().size());
        for (JsonNode p : r.body().path("items")) {
            R s = call("GET", p.path("schema").asText(), as("viewer"), null);
            assertThat(s.status()).isEqualTo(200);
            assertThat(s.body().path("$schema").asText()).contains("json-schema.org");
        }
        assertThat(call("GET", "/admin/v1/plugins/nope/schema", as("viewer"), null).status()).isEqualTo(404);
    }

    @Test
    void paginationAndUnknownPaths() throws Exception {
        seed();
        R first = call("GET", "/admin/v1/routes?limit=1", as("viewer"), null);
        assertThat(first.body().path("items")).hasSize(1);
        if (first.body().has("nextCursor")) {
            R next = call("GET", "/admin/v1/routes?limit=1&cursor=" + first.body().path("nextCursor").asText(),
                    as("viewer"), null);
            assertThat(next.body().path("items").get(0).path("key").asText())
                    .isNotEqualTo(first.body().path("items").get(0).path("key").asText());
        }
        assertThat(call("GET", "/admin/v1/routes?limit=0", as("viewer"), null).status()).isEqualTo(400);
        assertThat(call("GET", "/admin/v1/nope", as("viewer"), null).status()).isEqualTo(404);
        assertThat(call("PATCH", "/admin/v1/me", as("viewer"), "{}").status()).isEqualTo(405);
        assertThat(call("GET", "/admin/v1/routes/..%2F..%2Fnodes", as("viewer"), null).status()).isIn(400, 404);
        R gw = call("GET", "/admin/v1/gateways", as("viewer"), null);
        assertThat(gw.status()).isEqualTo(200);
        assertThat(gw.body().path("configRevision").asLong()).isPositive();
    }

    @Test
    void rollbackThroughTheApi() throws Exception {
        seed();
        String cs = newChangeSet("rb");
        assertThat(call("PUT", "/admin/v1/routes/rb?changeset=" + cs, as("editor"),
                "{\"spec\":{\"path\":\"/rb\",\"serviceId\":\"s\"}}").status()).isEqualTo(200);
        R p = call("POST", "/admin/v1/changesets/" + cs + "/publish", as("publisher"), null);
        long seq = p.body().path("seq").asLong();
        assertThat(kv.value(PREFIX + "routes/rb")).isPresent();
        assertThat(call("POST", "/admin/v1/history/" + seq + "/rollback", as("editor"), null).status())
                .isEqualTo(403);
        R rb = call("POST", "/admin/v1/history/" + seq + "/rollback", as("publisher"), null);
        assertThat(rb.status()).as(String.valueOf(rb.body())).isEqualTo(200);
        assertThat(kv.value(PREFIX + "routes/rb")).isEmpty();
        R entry = call("GET", "/admin/v1/history/" + rb.body().path("seq").asLong(), as("viewer"), null);
        assertThat(entry.body().path("rollbackOf").asLong()).isEqualTo(seq);
    }
}
