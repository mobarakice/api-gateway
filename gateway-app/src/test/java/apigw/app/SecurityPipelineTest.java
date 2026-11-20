package apigw.app;

import static apigw.plugins.testing.TestJwt.header;
import static apigw.plugins.testing.TestJwt.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import apigw.core.plugin.PluginServices;
import apigw.core.pipeline.Pipeline;
import apigw.core.snapshot.Snapshot;
import apigw.core.snapshot.SnapshotCompiler;
import apigw.core.snapshot.SnapshotHolder;
import apigw.model.ApiKeyHash;
import apigw.model.ConfigDecoder;
import apigw.model.ConfigException;
import apigw.plugins.BuiltinPlugins;
import apigw.plugins.jwt.OidcKeySources;
import apigw.plugins.testing.TestJwt;
import apigw.proxy.HttpForwarder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.PemKeyCertOptions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The M4 security suite, in process: real gateway pipeline and forwarder, a real echo upstream, a
 * real HTTPS OIDC provider fixture (TestPki CA), and tokens signed with JDK crypto. Runs on the JVM
 * and as a native image ({@code nativeTest}).
 */
class SecurityPipelineTest {

    private static final Path TLS = Path.of(System.getProperty("gw.test.tls.dir", "build/test-tls"));
    private static final String ISS = "https://idp.test";
    private static final KeyPair RSA = TestJwt.rsa();
    private static final KeyPair EC = TestJwt.ec256();
    private static final KeyPair IDP_KEY_1 = TestJwt.rsa();
    private static final KeyPair IDP_KEY_2 = TestJwt.ec256();
    private static final String SECRET_A = ApiKeyHash.newSecret();
    private static final String SECRET_B = ApiKeyHash.newSecret();
    private static final String SECRET_G = ApiKeyHash.newSecret();

    private static Vertx vertx;
    private static OidcKeySources oidc;
    private static SnapshotCompiler compiler;
    private static final SnapshotHolder HOLDER = new SnapshotHolder();
    private static final AtomicReference<String> IDP_JWKS = new AtomicReference<>();
    private static final AtomicInteger IDP_JWKS_HITS = new AtomicInteger();
    private static volatile int idpPort;
    private static volatile int upstreamPort;
    private static volatile int gatewayPort;
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void start() throws Exception {
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2));
        HttpServer upstream = await(vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0))
                .requestHandler(req -> {
                    JsonObject h = new JsonObject();
                    req.headers().forEach(e -> h.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue()));
                    req.response().putHeader("content-type", "application/json")
                            .end(new JsonObject().put("path", req.path()).put("headers", h).encode());
                }).listen());
        upstreamPort = upstream.actualPort();

        IDP_JWKS.set(TestJwt.jwks(TestJwt.jwk(IDP_KEY_1, "idp-1", "RS256")));
        HttpServer idp = await(vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0)
                .setSsl(true).setKeyCertOptions(new PemKeyCertOptions()
                        .setCertPath(TLS.resolve("server.pem").toString())
                        .setKeyPath(TLS.resolve("server-key.pem").toString())))
                .requestHandler(req -> {
                    String self = "https://localhost:" + req.localAddress().port();
                    switch (req.path()) {
                        case "/.well-known/openid-configuration" -> req.response().end(new JsonObject()
                                .put("issuer", self).put("jwks_uri", self + "/jwks").encode());
                        case "/jwks" -> {
                            IDP_JWKS_HITS.incrementAndGet();
                            req.response().end(IDP_JWKS.get());
                        }
                        default -> req.response().setStatusCode(404).end();
                    }
                }).listen());
        idpPort = idp.actualPort();

        oidc = new OidcKeySources(vertx, Duration.ofSeconds(5));
        compiler = new SnapshotCompiler(BuiltinPlugins.registry(),
                new PluginServices(Map.of(OidcKeySources.class, oidc)), Clock.systemUTC());
        publish(config());

        int[] port = new int[1];
        await(vertx.deployVerticle(new VerticleBase() {
            private HttpForwarder forwarder;

            @Override
            public Future<?> start() {
                forwarder = new HttpForwarder(vertx);
                return vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0))
                        .requestHandler(new Pipeline(HOLDER, forwarder)).listen()
                        .onSuccess(s -> port[0] = s.actualPort());
            }
        }));
        gatewayPort = port[0];
    }

    @AfterAll
    static void stop() throws Exception {
        await(vertx.close());
    }

    private static Snapshot publish(String json) throws ConfigException {
        Snapshot s = compiler.compile(ConfigDecoder.decode(json.getBytes(StandardCharsets.UTF_8)));
        HOLDER.publish(s);
        compiler.services().published(s.resources());
        return s;
    }

    private static String idpIssuer() {
        return "https://localhost:" + idpPort;
    }

    private static String config() {
        String jwks = TestJwt.jwks(TestJwt.jwk(RSA, "k-rsa", "RS256"), TestJwt.jwk(EC, "k-ec", "ES256"));
        String jwt = """
                {"name": "jwt", "config": {"issuers": [{"issuer": "%s", "audiences": ["orders"],
                  "algorithms": ["RS256", "ES256"], "jwks": %s, "rolesClaim": "realm.roles",
                  "consumerClaim": "cid", "tenantClaim": "tid"}]}}""".replace('\n', ' ').formatted(ISS, jwks);
        String oidcJwt = """
                {"name": "jwt", "config": {"issuers": [{"issuer": "%s", "audiences": ["orders"],
                  "algorithms": ["RS256", "ES256"], "discovery": true, "caFile": "%s",
                  "minRefreshSeconds": 1}]}}""".replace('\n', ' ').formatted(idpIssuer(), TLS.resolve("ca.pem"));
        return """
                {"schemaVersion": 1,
                 "settings": {"upstreamAllowCidrs": ["127.0.0.0/8"]},
                 "upstreams": [{"id": "u", "targets": [{"host": "127.0.0.1", "port": %1$d}]},
                               {"id": "ua", "tenantId": "a", "targets": [{"host": "127.0.0.1", "port": %1$d}]}],
                 "services": [{"id": "s", "upstreamId": "u"}, {"id": "sa", "tenantId": "a", "upstreamId": "ua"}],
                 "routes": [
                   {"id": "r-jwt", "path": "/jwt", "serviceId": "s", "plugins": [%2$s,
                      {"name": "authz", "config": {"policies": ["p-reader", "p-no-delete", "p-eu-only"]}}]},
                   {"id": "r-oidc", "path": "/oidc", "serviceId": "s", "plugins": [%3$s]},
                   {"id": "r-key", "path": "/key", "serviceId": "s", "plugins": [{"name": "key-auth"},
                      {"name": "headers", "config": {"request": {"set": {"x-plan": "free"}}}}]},
                   {"id": "r-a", "path": "/tenant-a", "tenantId": "a", "serviceId": "sa",
                      "plugins": [{"name": "key-auth"}]},
                   {"id": "r-a-jwt", "path": "/tenant-a-jwt", "tenantId": "a", "serviceId": "sa", "plugins": [%2$s]}
                 ],
                 "consumers": [
                   {"id": "c-a", "tenantId": "a", "username": "alice", "roles": ["reader"],
                    "plugins": [{"name": "headers", "config": {"request": {"set": {"x-plan": "gold"}}}}]},
                   {"id": "c-b", "tenantId": "b", "username": "bob"},
                   {"id": "c-g", "username": "global-svc", "roles": ["reader"]}
                 ],
                 "credentials": [
                   {"id": "k-a", "consumerId": "c-a", "tenantId": "a", "type": "api-key",
                    "keyId": "keyA_0001", "hash": "%4$s"},
                   {"id": "k-b", "consumerId": "c-b", "tenantId": "b", "type": "api-key",
                    "keyId": "keyB_0001", "hash": "%5$s"},
                   {"id": "k-g", "consumerId": "c-g", "type": "api-key", "keyId": "keyG_0001", "hash": "%6$s"}
                 ],
                 "authzPolicies": [
                   {"id": "p-reader", "effect": "allow", "routes": ["r-jwt"], "subjects": {"roles": ["reader"]}},
                   {"id": "p-no-delete", "effect": "deny", "methods": ["DELETE"]},
                   {"id": "p-eu-only", "effect": "deny", "subjects": {"anyAuthenticated": true},
                    "condition": {"all": [{"op": "exists", "attr": "principal.attr.region"},
                                          {"op": "ne", "attr": "principal.attr.region", "value": "eu"}]}}
                 ],
                 "revokedTokens": [{"id": "rt-1", "jti": "stolen-1", "issuer": "%7$s",
                                    "expiresAt": "2099-01-01T00:00:00Z"}]
                }""".replace('\n', ' ').formatted(upstreamPort, jwt, oidcJwt, ApiKeyHash.hash(SECRET_A),
                ApiKeyHash.hash(SECRET_B), ApiKeyHash.hash(SECRET_G), ISS);
    }

    private static String claims(String extra) {
        long now = System.currentTimeMillis() / 1000;
        return "{\"iss\":\"" + ISS + "\",\"sub\":\"user-1\",\"aud\":\"orders\",\"iat\":" + (now - 5) + ",\"exp\":"
                + (now + 300) + ",\"realm\":{\"roles\":[\"reader\"]}" + extra + "}";
    }

    private static String rs(String claims) {
        return sign(header("RS256", "k-rsa"), claims, "RS256", RSA.getPrivate());
    }

    private static HttpResponse<String> call(String method, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
                .timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject upstreamHeaders(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return new JsonObject(r.body()).getJsonObject("headers");
    }

    /** No 4xx/5xx body may echo credentials, tokens, upstream addresses, or config details. */
    private static void assertGeneric(HttpResponse<String> r, int status, String... secrets) {
        assertThat(r.statusCode()).isEqualTo(status);
        assertThat(r.body()).contains("requestId").doesNotContain("127.0.0.1", String.valueOf(upstreamPort),
                "Exception", "p-reader", "p-no-delete", "k-rsa");
        for (String s : secrets) {
            assertThat(r.body()).doesNotContain(s);
            r.headers().map().values().forEach(v -> assertThat(String.join(",", v)).doesNotContain(s));
        }
    }

    @Test
    void validJwtIsAuthenticatedAndIdentityIsForwardedNotSpoofable() throws Exception {
        HttpResponse<String> r = call("GET", "/jwt", "authorization", "Bearer " + rs(claims("")),
                "x-consumer-id", "spoofed", "x-authenticated-user", "admin", "x-tenant-id", "evil");
        JsonObject h = upstreamHeaders(r);
        assertThat(h.getString("x-authenticated-user")).isEqualTo("user-1");
        assertThat(h.containsKey("x-consumer-id")).isFalse();
        assertThat(h.containsKey("x-tenant-id")).isFalse();
        assertThat(h.getString("authorization")).startsWith("Bearer ");
        // ES256 from the same pinned set works too.
        String es = sign(header("ES256", "k-ec"), claims(""), "ES256", EC.getPrivate());
        assertThat(call("GET", "/jwt", "authorization", "Bearer " + es).statusCode()).isEqualTo(200);
    }

    @Test
    void invalidJwtsGetAGeneric401() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        HttpResponse<String> missing = call("GET", "/jwt");
        assertGeneric(missing, 401);
        assertThat(missing.headers().firstValue("www-authenticate")).hasValue("Bearer realm=\"gateway\"");
        String expired = rs(claims("").replaceFirst("\"exp\":\\d+", "\"exp\":" + (now - 3600)));
        String wrongIss = rs(claims("").replace(ISS, "https://evil.test"));
        String wrongAud = rs(claims("").replace("\"aud\":\"orders\"", "\"aud\":\"billing\""));
        String confusion = sign(header("HS256", "k-rsa"), claims(""), "HS256", RSA.getPublic().getEncoded());
        String none = TestJwt.b64(header("none", null)) + "." + TestJwt.b64(claims("")) + ".";
        String otherKey = sign(header("RS256", "k-rsa"), claims(""), "RS256", TestJwt.rsa().getPrivate());
        String revoked = rs(claims(",\"jti\":\"stolen-1\""));
        for (String t : new String[] {expired, wrongIss, wrongAud, confusion, none, otherKey, revoked, "garbage"}) {
            HttpResponse<String> r = call("GET", "/jwt", "authorization", "Bearer " + t);
            assertGeneric(r, 401, t);
            assertThat(r.headers().firstValue("www-authenticate")).hasValue(
                    "Bearer realm=\"gateway\", error=\"invalid_token\"");
        }
        assertGeneric(call("GET", "/jwt", "authorization", "Basic dXNlcjpwYXNz"), 401);
    }

    @Test
    void rbacAndAbacPoliciesDenyOverAllow() throws Exception {
        // No role -> no allow matches -> 403 (authenticated, so not 401).
        String noRole = rs(claims("").replace("\"roles\":[\"reader\"]", "\"roles\":[]"));
        assertGeneric(call("GET", "/jwt", "authorization", "Bearer " + noRole), 403, noRole);
        // Explicit deny beats the role allow.
        assertGeneric(call("DELETE", "/jwt", "authorization", "Bearer " + rs(claims(""))), 403);
        // ABAC: a principal attribute (claim) other than eu is denied; eu is allowed.
        assertGeneric(call("GET", "/jwt", "authorization", "Bearer " + rs(claims(",\"region\":\"us\""))), 403);
        assertThat(call("GET", "/jwt", "authorization", "Bearer " + rs(claims(",\"region\":\"eu\""))).statusCode())
                .isEqualTo(200);
        // Roles can come from the mapped consumer instead of the token.
        String viaConsumer = rs(claims(",\"cid\":\"c-g\"").replace("\"roles\":[\"reader\"]", "\"roles\":[]"));
        JsonObject h = upstreamHeaders(call("GET", "/jwt", "authorization", "Bearer " + viaConsumer));
        assertThat(h.getString("x-consumer-id")).isEqualTo("c-g");
        assertThat(h.getString("x-consumer-username")).isEqualTo("global-svc");
    }

    @Test
    void apiKeysAreCheckedHiddenAndUniformlyRejected() throws Exception {
        JsonObject h = upstreamHeaders(call("GET", "/key", "x-api-key", "keyG_0001." + SECRET_G));
        assertThat(h.getString("x-consumer-id")).isEqualTo("c-g");
        assertThat(h.getString("x-credential-identifier")).isEqualTo("c-g/k-g");
        assertThat(h.containsKey("x-api-key")).isFalse();
        for (String bad : new String[] {"keyG_0001." + SECRET_A, "keyG_0001." + SECRET_G + "x",
                "nokey_0001." + SECRET_G, SECRET_G, "keyG_0001.", "keyG_0001"}) {
            HttpResponse<String> r = call("GET", "/key", "x-api-key", bad);
            assertGeneric(r, 401, SECRET_G, SECRET_A);
        }
        assertGeneric(call("GET", "/key"), 401);
    }

    @Test
    void consumerPluginConfigOverridesTheRoute() throws Exception {
        assertThat(upstreamHeaders(call("GET", "/key", "x-api-key", "keyG_0001." + SECRET_G)).getString("x-plan"))
                .isEqualTo("free");
        assertThat(upstreamHeaders(call("GET", "/key", "x-api-key", "keyA_0001." + SECRET_A)).getString("x-plan"))
                .isEqualTo("gold");
    }

    @Test
    void tenantsAreIsolated() throws Exception {
        JsonObject h = upstreamHeaders(call("GET", "/tenant-a", "x-api-key", "keyA_0001." + SECRET_A));
        assertThat(h.getString("x-tenant-id")).isEqualTo("a");
        // Valid credential of tenant b, and a valid global credential: both refused on a tenant-a route.
        assertGeneric(call("GET", "/tenant-a", "x-api-key", "keyB_0001." + SECRET_B), 403, SECRET_B);
        assertGeneric(call("GET", "/tenant-a", "x-api-key", "keyG_0001." + SECRET_G), 403, SECRET_G);
        // A token claiming tenant a without a consumer of tenant a is accepted only for that tenant.
        assertThat(call("GET", "/tenant-a-jwt", "authorization", "Bearer " + rs(claims(",\"tid\":\"a\"")))
                .statusCode()).isEqualTo(200);
        assertGeneric(call("GET", "/tenant-a-jwt", "authorization", "Bearer " + rs(claims(",\"tid\":\"b\""))), 403);
        assertGeneric(call("GET", "/tenant-a-jwt", "authorization", "Bearer " + rs(claims(""))), 403);
        // Token says tenant a but maps to a consumer of tenant b: rejected, never guessed.
        assertGeneric(call("GET", "/tenant-a-jwt", "authorization", "Bearer "
                + rs(claims(",\"tid\":\"a\",\"cid\":\"c-b\""))), 401);
        // A tenant route can never be configured without authentication.
        assertThatThrownBy(() -> compiler.compile(ConfigDecoder.decode(config()
                .replace("\"plugins\": [{\"name\": \"key-auth\"}]}", "\"plugins\": []}")
                .getBytes(StandardCharsets.UTF_8)))).isInstanceOf(ConfigException.class)
                .hasMessageContaining("tenant-scoped routes need an authentication plugin");
    }

    @Test
    void oidcDiscoveryFetchesKeysAndFollowsRotationWithRateLimit() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        String base = "{\"iss\":\"" + idpIssuer() + "\",\"sub\":\"svc\",\"aud\":\"orders\",\"exp\":" + (now + 300)
                + "}";
        String t1 = sign(header("RS256", "idp-1"), base, "RS256", IDP_KEY_1.getPrivate());
        assertThat(call("GET", "/oidc", "authorization", "Bearer " + t1).statusCode()).isEqualTo(200);
        // The IdP rotates in a new key; a token with the new kid triggers one refresh and then passes.
        IDP_JWKS.set(TestJwt.jwks(TestJwt.jwk(IDP_KEY_1, "idp-1", "RS256"), TestJwt.jwk(IDP_KEY_2, "idp-2", "ES256")));
        Thread.sleep(1100);
        String t2 = sign(header("ES256", "idp-2"), base, "ES256", IDP_KEY_2.getPrivate());
        assertThat(call("GET", "/oidc", "authorization", "Bearer " + t2).statusCode()).isEqualTo(200);
        // Forged kids cannot make the gateway hammer the IdP: refreshes are rate-limited.
        java.security.PrivateKey attacker = TestJwt.rsa().getPrivate();
        String[] forged = new String[20];
        for (int i = 0; i < forged.length; i++) {
            forged[i] = sign(header("RS256", "forged-" + i), base, "RS256", attacker);
        }
        int before = IDP_JWKS_HITS.get();
        long started = System.nanoTime();
        for (String f : forged) {
            assertGeneric(call("GET", "/oidc", "authorization", "Bearer " + f), 401, f);
        }
        // minRefreshSeconds is 1: at most one unknown-kid refresh per started second (+1 periodic slack).
        long windows = (System.nanoTime() - started) / 1_000_000_000L + 1;
        assertThat(IDP_JWKS_HITS.get() - before).isLessThanOrEqualTo((int) windows + 1);
    }

    static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
