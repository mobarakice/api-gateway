package apigw.it;

import static apigw.plugins.testing.TestJwt.header;
import static apigw.plugins.testing.TestJwt.sign;
import static org.assertj.core.api.Assertions.assertThat;

import apigw.model.ApiKeyHash;
import apigw.plugins.testing.TestJwt;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.PemKeyCertOptions;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The M4 security suite against the real gateway process (JVM or native binary): JWT with OIDC
 * discovery over TLS and every supported signature family, HMAC from a secret file, API keys, RBAC,
 * tenant isolation, alg confusion, revocation, smuggling, header limits, identity spoofing, SSRF
 * at config load, and no tokens or secrets in responses or logs.
 */
class SecurityIT {

    private static final Path TLS = Path.of(System.getProperty("gw.test.tls.dir", "build/test-tls"));
    private static final KeyPair RSA = TestJwt.rsa();
    private static final KeyPair EC = TestJwt.ec256();
    private static final KeyPair ED = TestJwt.ed25519();
    private static final byte[] HMAC = ApiKeyHash.newSecret().getBytes(StandardCharsets.US_ASCII);
    private static final String KEY_SECRET = ApiKeyHash.newSecret();
    private static final String KEY_SECRET_B = ApiKeyHash.newSecret();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static Vertx vertx;
    private static volatile int upstreamPort;
    private static volatile int idpPort;

    @BeforeAll
    static void servers() throws Exception {
        vertx = Vertx.vertx();
        HttpServer up = await(vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0))
                .requestHandler(req -> {
                    JsonObject h = new JsonObject();
                    req.headers().forEach(e -> h.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue()));
                    req.response().putHeader("content-type", "application/json")
                            .end(new JsonObject().put("headers", h).encode());
                }).listen());
        upstreamPort = up.actualPort();
        String jwks = TestJwt.jwks(TestJwt.jwk(RSA, "rsa", "RS256"), TestJwt.jwk(EC, "ec", "ES256"),
                TestJwt.jwk(ED, "ed", "EdDSA"));
        HttpServer idp = await(vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0)
                .setSsl(true).setKeyCertOptions(new PemKeyCertOptions()
                        .setCertPath(TLS.resolve("server.pem").toString())
                        .setKeyPath(TLS.resolve("server-key.pem").toString())))
                .requestHandler(req -> {
                    String self = "https://localhost:" + req.localAddress().port();
                    switch (req.path()) {
                        case "/.well-known/openid-configuration" -> req.response().end(new JsonObject()
                                .put("issuer", self).put("jwks_uri", self + "/keys").encode());
                        case "/keys" -> req.response().end(jwks);
                        default -> req.response().setStatusCode(404).end();
                    }
                }).listen());
        idpPort = idp.actualPort();
    }

    @AfterAll
    static void stop() throws Exception {
        await(vertx.close());
    }

    private static String issuer() {
        return "https://localhost:" + idpPort;
    }

    private static String config(Path hmacFile, String extraUpstream) {
        return """
                {"schemaVersion": 1,
                 "settings": {"upstreamAllowCidrs": ["127.0.0.0/8"]},
                 "upstreams": [{"id": "u", "targets": [{"host": "127.0.0.1", "port": %1$d}]},
                               {"id": "ua", "tenantId": "a", "targets": [{"host": "127.0.0.1", "port": %1$d}]}%6$s],
                 "services": [{"id": "s", "upstreamId": "u"}, {"id": "sa", "tenantId": "a", "upstreamId": "ua"}],
                 "routes": [
                   {"id": "oidc", "path": "/oidc", "serviceId": "s", "plugins": [
                     {"name": "jwt", "config": {"issuers": [{"issuer": "%2$s", "audiences": ["api"],
                       "algorithms": ["RS256", "ES256", "EdDSA"], "discovery": true, "caFile": "%3$s",
                       "rolesClaim": "roles"}]}},
                     {"name": "authz", "config": {"policies": ["readers"]}}]},
                   {"id": "hmac", "path": "/hmac", "serviceId": "s", "plugins": [
                     {"name": "jwt", "config": {"issuers": [{"issuer": "internal", "audiences": ["api"],
                       "algorithms": ["HS256"], "secretFile": "%4$s"}], "forwardToken": false}}]},
                   {"id": "keys", "path": "/keys", "serviceId": "s", "plugins": [{"name": "key-auth"}]},
                   {"id": "tenant-a", "path": "/tenant-a", "tenantId": "a", "serviceId": "sa",
                    "plugins": [{"name": "key-auth"}]}
                 ],
                 "consumers": [{"id": "ca", "tenantId": "a", "username": "alice"}, {"id": "cb", "tenantId": "b"}],
                 "credentials": [
                   {"id": "k", "consumerId": "ca", "tenantId": "a", "type": "api-key", "keyId": "itkey_a01",
                    "hash": "%5$s"},
                   {"id": "k", "consumerId": "cb", "tenantId": "b", "type": "api-key", "keyId": "itkey_b01",
                    "hash": "%7$s"}],
                 "authzPolicies": [{"id": "readers", "effect": "allow", "subjects": {"roles": ["reader"]}}],
                 "revokedTokens": [{"id": "rv", "jti": "revoked-1", "issuer": "%2$s",
                                    "expiresAt": "2099-01-01T00:00:00Z"}]
                }""".replace('\n', ' ').formatted(upstreamPort, issuer(), TLS.resolve("ca.pem"), hmacFile,
                ApiKeyHash.hash(KEY_SECRET), extraUpstream, ApiKeyHash.hash(KEY_SECRET_B));
    }

    private static String claims(String iss, String extra) {
        long now = System.currentTimeMillis() / 1000;
        return "{\"iss\":\"" + iss + "\",\"sub\":\"it-user\",\"aud\":\"api\",\"exp\":" + (now + 300)
                + ",\"roles\":[\"reader\"]" + extra + "}";
    }

    private static HttpResponse<String> get(int port, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String raw(int port, String request) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            try {
                int n;
                while ((n = in.read(tmp)) > 0) {
                    buf.write(tmp, 0, n);
                }
            } catch (SocketTimeoutException e) {
                // Server kept the connection open after responding; what we have is the answer.
            }
            return buf.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private static void writeConfig(Path file, String json) throws Exception {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Test
    void securitySuiteAgainstTheRealProcess(@TempDir Path dir) throws Exception {
        Path hmacFile = Files.write(dir.resolve("hmac.key"), HMAC);
        Path file = dir.resolve("gateway.json");
        writeConfig(file, config(hmacFile, ""));
        int http = Ports.free();
        Map<String, String> env = new HashMap<>(Map.of("GW_BIND_HOST", "127.0.0.1",
                "GW_HTTP_PORT", Integer.toString(http), "GW_HTTPS_PORT", Integer.toString(Ports.free()),
                "GW_STATUS_PORT", Integer.toString(Ports.free()), "GW_EVENT_LOOPS", "2",
                "GW_SHUTDOWN_GRACE_SECONDS", "5", "GW_CONFIG_FILE", file.toString(), "GW_CONFIG_POLL_MS", "100"));
        List<String> secrets = new ArrayList<>(List.of(KEY_SECRET, KEY_SECRET_B, new String(HMAC,
                StandardCharsets.US_ASCII)));
        try (GatewayProcess gw = GatewayProcess.start(List.of(), env)) {
            gw.awaitLine(l -> l.contains("\"msg\":\"ready"), Duration.ofSeconds(30));
            String iss = issuer();

            // Every signature family, through OIDC discovery over TLS (JDK crypto in the native image).
            for (String[] alg : new String[][] {{"RS256", "rsa"}, {"ES256", "ec"}, {"EdDSA", "ed"}}) {
                Object key = switch (alg[0]) {
                    case "RS256" -> RSA.getPrivate();
                    case "ES256" -> EC.getPrivate();
                    default -> ED.getPrivate();
                };
                String t = sign(header(alg[0], alg[1]), claims(iss, ""), alg[0], key);
                secrets.add(t);
                HttpResponse<String> r = get(http, "/oidc", "authorization", "Bearer " + t,
                        "x-authenticated-user", "spoofed-admin");
                assertThat(r.statusCode()).as(alg[0] + " " + r.body()).isEqualTo(200);
                assertThat(new JsonObject(r.body()).getJsonObject("headers").getString("x-authenticated-user"))
                        .isEqualTo("it-user");
            }
            // Rejections: alg confusion, none, expired, wrong audience, wrong issuer, revoked, no role.
            long now = System.currentTimeMillis() / 1000;
            List<String> bad = List.of(
                    sign(header("HS256", "rsa"), claims(iss, ""), "HS256", RSA.getPublic().getEncoded()),
                    TestJwt.b64(header("none", null)) + "." + TestJwt.b64(claims(iss, "")) + ".",
                    sign(header("RS256", "rsa"), claims(iss, "").replaceFirst("\"exp\":\\d+", "\"exp\":" + (now - 999)),
                            "RS256", RSA.getPrivate()),
                    sign(header("RS256", "rsa"), claims(iss, "").replace("\"aud\":\"api\"", "\"aud\":\"other\""),
                            "RS256", RSA.getPrivate()),
                    sign(header("RS256", "rsa"), claims("https://evil.test", ""), "RS256", RSA.getPrivate()),
                    sign(header("RS256", "rsa"), claims(iss, ",\"jti\":\"revoked-1\""), "RS256", RSA.getPrivate()));
            for (String t : bad) {
                secrets.add(t);
                HttpResponse<String> r = get(http, "/oidc", "authorization", "Bearer " + t);
                assertThat(r.statusCode()).isEqualTo(401);
                assertThat(r.body()).doesNotContain(t, "127.0.0.1", "localhost");
            }
            assertThat(get(http, "/oidc").statusCode()).isEqualTo(401);
            String noRole = sign(header("RS256", "rsa"), claims(iss, "").replace("[\"reader\"]", "[]"), "RS256",
                    RSA.getPrivate());
            assertThat(get(http, "/oidc", "authorization", "Bearer " + noRole).statusCode()).isEqualTo(403);

            // HMAC issuer from a secret file; the token is not forwarded upstream.
            String hs = sign(header("HS256", null), claims("internal", ""), "HS256", HMAC);
            secrets.add(hs);
            HttpResponse<String> hr = get(http, "/hmac", "authorization", "Bearer " + hs);
            assertThat(hr.statusCode()).isEqualTo(200);
            assertThat(new JsonObject(hr.body()).getJsonObject("headers").containsKey("authorization")).isFalse();
            String forgedHs = sign(header("HS256", null), claims("internal", ""), "HS256",
                    "wrong-secret-wrong-secret-wrong-secret".getBytes(StandardCharsets.US_ASCII));
            assertThat(get(http, "/hmac", "authorization", "Bearer " + forgedHs).statusCode()).isEqualTo(401);

            // API keys and tenant isolation.
            assertThat(get(http, "/keys", "x-api-key", "itkey_a01." + KEY_SECRET).statusCode()).isEqualTo(200);
            assertThat(get(http, "/keys", "x-api-key", "itkey_a01." + KEY_SECRET_B).statusCode()).isEqualTo(401);
            assertThat(get(http, "/tenant-a", "x-api-key", "itkey_a01." + KEY_SECRET).statusCode()).isEqualTo(200);
            HttpResponse<String> cross = get(http, "/tenant-a", "x-api-key", "itkey_b01." + KEY_SECRET_B);
            assertThat(cross.statusCode()).isEqualTo(403);
            assertThat(cross.body()).doesNotContain("cb", "tenant", KEY_SECRET_B);

            // Request smuggling, ambiguous framing and oversized headers never reach a plugin or upstream.
            assertThat(raw(http, "POST /keys HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\n"
                    + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n0\r\n\r\n")).startsWith("HTTP/1.1 400");
            assertThat(raw(http, "POST /keys HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\nContent-Length: 2\r\n"
                    + "Connection: close\r\n\r\nab")).startsWith("HTTP/1.1 400");
            assertThat(raw(http, "GET /keys HTTP/1.1\r\nHost: x\r\nx-big: " + "a".repeat(20_000)
                    + "\r\nConnection: close\r\n\r\n")).startsWith("HTTP/1.1 431");
            // Header injection via obs-fold: the continuation is unfolded into the previous value
            // (RFC 9112 5.2), so it never becomes a header of its own upstream.
            String folded = raw(http, "GET /keys HTTP/1.1\r\nHost: x\r\nx-api-key: itkey_a01." + KEY_SECRET
                    + "\r\nx-note: a\r\n x-injected: folded\r\nConnection: close\r\n\r\n");
            assertThat(folded).startsWith("HTTP/1.1 200");
            JsonObject foldedHeaders = new JsonObject(folded.substring(folded.indexOf("\r\n\r\n") + 4))
                    .getJsonObject("headers");
            assertThat(foldedHeaders.containsKey("x-injected")).isFalse();
            assertThat(foldedHeaders.getString("x-note")).doesNotContain("\r", "\n");

            // SSRF: a change pointing an upstream at cloud metadata is rejected; traffic keeps flowing.
            writeConfig(file, config(hmacFile, ", {\"id\": \"imds\", \"targets\": [{\"host\": \"169.254.169.254\","
                    + " \"port\": 80}]}"));
            gw.awaitLine(l -> l.contains("config file change rejected") && l.contains("denied range"),
                    Duration.ofSeconds(10));
            assertThat(get(http, "/keys", "x-api-key", "itkey_a01." + KEY_SECRET).statusCode()).isEqualTo(200);

            assertThat(gw.terminate(Duration.ofSeconds(15))).isEqualTo(143);
            List<String> out = gw.output();
            assertThat(out).noneMatch(l -> l.contains("Exception in thread") || l.contains("UnsupportedFeature")
                    || l.contains("NoClassDefFoundError") || l.contains("NoSuchAlgorithm"));
            // The cross-tenant attempt is audited, by credential and route, never with the secret.
            assertThat(out).anyMatch(l -> l.contains("cross-tenant request denied") && l.contains("route=tenant-a"));
            for (String s : secrets) {
                assertThat(out).as("secret or token in logs").noneMatch(l -> l.contains(s));
            }
        }
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
