package apigw.proxy;

import static apigw.proxy.ProxyHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpForwarderTest {

    private ProxyHarness h;
    private ProxyHarness.Echo a;
    private ProxyHarness.Echo b;

    @BeforeEach
    void setUp() throws Exception {
        h = new ProxyHarness();
        a = h.upstream("a", true);
        b = h.upstream("b", true);
        h.startGateway(new HttpServerOptions().setHttp2ClearTextEnabled(true));
    }

    @AfterEach
    void tearDown() throws Exception {
        h.close();
    }

    private String config(String upstreamExtra, String routeExtra, String settings) {
        // Loopback upstreams: allow-listed for the SSRF rules, as any test or dev setup must.
        String allow = "\"upstreamAllowCidrs\":[\"127.0.0.0/8\"]";
        return "{\"schemaVersion\":1,\"settings\":{" + (settings.isEmpty() ? allow : settings + "," + allow) + "},"
                + "\"upstreams\":[{\"id\":\"u\",\"targets\":[{\"host\":\"127.0.0.1\",\"port\":" + a.port + "}]"
                + upstreamExtra + "},"
                + "{\"id\":\"two\",\"targets\":[{\"host\":\"127.0.0.1\",\"port\":" + a.port + ",\"weight\":3},"
                + "{\"host\":\"127.0.0.1\",\"port\":" + b.port + ",\"weight\":1}],"
                + "\"balancer\":\"weighted-round-robin\"},"
                + "{\"id\":\"dead\",\"targets\":[{\"host\":\"127.0.0.1\",\"port\":1}]}],"
                + "\"services\":[{\"id\":\"s\",\"upstreamId\":\"u\"},{\"id\":\"s2\",\"upstreamId\":\"two\"},"
                + "{\"id\":\"sd\",\"upstreamId\":\"dead\"}],"
                + "\"routes\":[{\"id\":\"main\",\"path\":\"/\",\"serviceId\":\"s\"" + routeExtra + "},"
                + "{\"id\":\"lb\",\"path\":\"/lb\",\"serviceId\":\"s2\"},"
                + "{\"id\":\"dead\",\"path\":\"/dead\",\"serviceId\":\"sd\"},"
                + "{\"id\":\"short\",\"path\":\"/t\",\"serviceId\":\"s\",\"timeouts\":{\"firstByteMs\":200}}]}";
    }

    record Resp(int status, MultiMap headers, Buffer body, HttpVersion version) {
        JsonObject json() {
            return body.toJsonObject();
        }
    }

    private Resp call(HttpClientOptions o, HttpMethod m, String uri, MultiMap headers, Buffer body)
            throws Exception {
        HttpClientAgent c = h.vertx.createHttpClient(o);
        try {
            return await(c.request(new RequestOptions().setMethod(m).setHost("127.0.0.1").setPort(h.gatewayPort)
                    .setURI(uri).setHeaders(headers == null ? MultiMap.caseInsensitiveMultiMap() : headers))
                    .compose(req -> body == null ? req.send() : req.send(body))
                    .compose(resp -> resp.body().map(bb -> new Resp(resp.statusCode(), resp.headers(), bb,
                            resp.version()))));
        } finally {
            await(c.close());
        }
    }

    private Resp get(String uri) throws Exception {
        return call(new HttpClientOptions(), HttpMethod.GET, uri, null, null);
    }

    @Test
    void proxiesAndRewritesForwardingHeaders() throws Exception {
        h.publish(config("", "", ""));
        MultiMap in = MultiMap.caseInsensitiveMultiMap()
                .add("x-forwarded-for", "6.6.6.6").add("forwarded", "for=6.6.6.6")
                .add("x-consumer-id", "spoofed").add("connection", "x-secret").add("x-secret", "1")
                .add("x-request-id", "client-chosen").add("x-keep", "yes");
        Resp r = call(new HttpClientOptions(), HttpMethod.GET, "/users/7?q=1&q=2", in, null);
        assertThat(r.status()).isEqualTo(200);
        JsonObject e = r.json();
        assertThat(e.getString("uri")).isEqualTo("/users/7?q=1&q=2");
        JsonObject hh = e.getJsonObject("headers");
        // Untrusted peer: client-supplied forwarding headers are replaced, not appended to.
        assertThat(hh.getString("x-forwarded-for")).isEqualTo("127.0.0.1");
        assertThat(hh.getString("forwarded")).isEqualTo("for=127.0.0.1;proto=http;host=\"127.0.0.1\"");
        assertThat(hh.getString("x-forwarded-proto")).isEqualTo("http");
        assertThat(hh.getString("via")).isEqualTo("1.1 apigw");
        assertThat(hh.getString("x-request-id")).matches("[0-9a-f]{16}");
        assertThat(hh.getString("x-request-id")).isEqualTo(r.headers().get("x-request-id"));
        assertThat(hh.containsKey("x-consumer-id")).isFalse();
        assertThat(hh.containsKey("x-secret")).isFalse();
        assertThat(hh.getString("x-keep")).isEqualTo("yes");
        assertThat(e.getString("authority")).isEqualTo("127.0.0.1:" + a.port);
        // Response hop-by-hop headers (and those named in Connection) do not reach the client.
        assertThat(r.headers().contains("x-hop")).isFalse();
        assertThat(r.headers().contains("keep-alive")).isFalse();
        assertThat(r.headers().get("via")).isEqualTo("1.1 apigw");
    }

    @Test
    void trustedProxyChainIsKeptAndAppended() throws Exception {
        h.publish(config("", "", "\"trustedProxies\":[\"127.0.0.0/8\"]"));
        MultiMap in = MultiMap.caseInsensitiveMultiMap().add("x-forwarded-for", "203.0.113.9, 10.0.0.2")
                .add("x-forwarded-proto", "https").add("forwarded", "for=203.0.113.9");
        JsonObject hh = call(new HttpClientOptions(), HttpMethod.GET, "/", in, null).json().getJsonObject("headers");
        assertThat(hh.getString("x-forwarded-for")).isEqualTo("203.0.113.9, 10.0.0.2, 127.0.0.1");
        assertThat(hh.getString("x-forwarded-proto")).isEqualTo("https");
        assertThat(hh.getString("forwarded")).startsWith("for=203.0.113.9, for=127.0.0.1");
    }

    @Test
    void preserveHostForwardsClientAuthority() throws Exception {
        h.publish(config("", ",\"preserveHost\":true", ""));
        MultiMap in = MultiMap.caseInsensitiveMultiMap();
        HttpClientAgent c = h.vertx.createHttpClient();
        try {
            JsonObject e = await(c.request(new RequestOptions().setHost("127.0.0.1").setPort(h.gatewayPort)
                    .setURI("/").setHeaders(in).setServer(null))
                    .compose(req -> req.authority(io.vertx.core.net.HostAndPort.create("api.example.com", 8443))
                            .send())
                    .compose(resp -> resp.body())).toJsonObject();
            assertThat(e.getString("authority")).isEqualTo("api.example.com:8443");
        } finally {
            await(c.close());
        }
    }

    @Test
    void streamsLargeBodiesBothWays() throws Exception {
        h.publish(config("", "", "\"maxRequestBodyBytes\":67108864"));
        Buffer big = Buffer.buffer(new byte[24 * 1024 * 1024]);
        Resp up = call(new HttpClientOptions(), HttpMethod.POST, "/upload", null, big);
        assertThat(up.json().getLong("bodyBytes")).isEqualTo(big.length());
        Resp down = get("/bytes/" + (16 * 1024 * 1024 + 5));
        assertThat(down.body().length()).isEqualTo(16 * 1024 * 1024 + 5);
        assertThat(down.headers().get("transfer-encoding")).isEqualTo("chunked");
    }

    @Test
    void chunkedUploadIsStreamedAndCapped() throws Exception {
        h.publish(config("", "", "\"maxRequestBodyBytes\":100000"));
        String ok = rawRequest("POST /c HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n0\r\n\r\n");
        assertThat(ok).startsWith("HTTP/1.1 200").contains("\"bodyBytes\":5");
        StringBuilder big = new StringBuilder("POST /c HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n");
        String chunk = "x".repeat(4096);
        for (int i = 0; i < 40; i++) {
            big.append("1000\r\n").append(chunk).append("\r\n");
        }
        big.append("0\r\n\r\n");
        assertThat(rawRequest(big.toString())).startsWith("HTTP/1.1 413");
        Resp declared = call(new HttpClientOptions(), HttpMethod.POST, "/c",
                MultiMap.caseInsensitiveMultiMap(), Buffer.buffer(new byte[100_001]));
        assertThat(declared.status()).isEqualTo(413);
    }

    @Test
    void mapsUpstreamFailuresToGenericErrors() throws Exception {
        h.publish(config("", "", ""));
        Resp dead = get("/dead");
        assertThat(dead.status()).isEqualTo(502);
        assertThat(dead.body().toString()).isEqualTo("{\"type\":\"about:blank\",\"title\":\"Bad Gateway\","
                + "\"status\":502,\"requestId\":\"" + dead.headers().get("x-request-id") + "\"}");
        Resp slow = get("/t/slow/2000");
        assertThat(slow.status()).isEqualTo(504);
        assertThat(slow.body().toString()).doesNotContain("127.0.0.1").doesNotContain(String.valueOf(a.port));
        assertThat(get("/status/503").status()).isEqualTo(503);
        assertThat(get("/status/204").status()).isEqualTo(204);
    }

    @Test
    void poolLimitFailsFastWith503() throws Exception {
        h.publish(config(",\"pool\":{\"maxConnections\":1,\"maxPending\":0}", "", ""));
        HttpClientAgent c = h.vertx.createHttpClient(new HttpClientOptions(),
                new io.vertx.core.http.PoolOptions().setHttp1MaxSize(4));
        try {
            var first = c.request(HttpMethod.GET, h.gatewayPort, "127.0.0.1", "/slow/800")
                    .compose(req -> req.send()).map(io.vertx.core.http.HttpClientResponse::statusCode);
            Thread.sleep(200);
            int second = await(c.request(HttpMethod.GET, h.gatewayPort, "127.0.0.1", "/slow/10")
                    .compose(req -> req.send()).map(io.vertx.core.http.HttpClientResponse::statusCode));
            assertThat(second).isEqualTo(503);
            assertThat(await(first)).isEqualTo(200);
        } finally {
            await(c.close());
        }
    }

    @Test
    void weightedRoundRobinSplitsByWeight() throws Exception {
        h.publish(config("", "", ""));
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            seen.merge(get("/lb").json().getString("upstream"), 1, Integer::sum);
        }
        assertThat(seen).containsEntry("a", 30).containsEntry("b", 10);
    }

    @Test
    void h2ClientAndH2cUpstream() throws Exception {
        h.publish(config(",\"http2\":true", "", ""));
        Resp r = call(new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false),
                HttpMethod.POST, "/h2", null, Buffer.buffer("abc"));
        assertThat(r.version()).isEqualTo(HttpVersion.HTTP_2);
        assertThat(r.json().getString("version")).isEqualTo("HTTP_2");
        assertThat(r.json().getLong("bodyBytes")).isEqualTo(3);
        assertThat(r.headers().get("via")).isEqualTo("1.1 apigw");
    }

    @Test
    void configSwapKeepsServingAndRetiresChangedPools() throws Exception {
        h.publish(config("", "", ""));
        assertThat(get("/").json().getString("upstream")).isEqualTo("a");
        h.publish(config("", "", "").replace("\"port\":" + a.port + "}]}", "\"port\":" + b.port + "}]}"));
        assertThat(get("/").json().getString("upstream")).isEqualTo("b");
    }

    @Test
    void rejectsSmugglingAndAmbiguousPaths() throws Exception {
        h.publish(config("", "", ""));
        assertThat(rawRequest("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "0\r\n\r\n")).startsWith("HTTP/1.1 400");
        assertThat(rawRequest("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\nContent-Length: 4\r\n\r\nabcd"))
                .startsWith("HTTP/1.1 400");
        assertThat(rawRequest("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: gzip, chunked\r\n\r\n0\r\n\r\n"))
                .startsWith("HTTP/1.1 400");
        assertThat(rawRequest("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n")).startsWith("HTTP/1.1 400");
        assertThat(rawRequest("GET / HTTP/1.1\r\n\r\n")).startsWith("HTTP/1.1 400");
        assertThat(rawRequest("GET /a/../admin HTTP/1.1\r\nHost: x\r\n\r\n")).startsWith("HTTP/1.1 400");
        assertThat(rawRequest("GET /a/%2e%2e/admin HTTP/1.1\r\nHost: x\r\n\r\n")).startsWith("HTTP/1.1 400");
        assertThat(rawRequest("GET /a%2fb HTTP/1.1\r\nHost: x\r\n\r\n")).startsWith("HTTP/1.1 400");
        assertThat(rawRequest("GET //a HTTP/1.1\r\nHost: x\r\n\r\n")).startsWith("HTTP/1.1 400");
        assertThat(rawRequest("CONNECT a:443 HTTP/1.1\r\nHost: a:443\r\n\r\n")).startsWith("HTTP/1.1 501");
        assertThat(rawRequest("GET /ok HTTP/1.1\r\nHost: x\r\n\r\n")).startsWith("HTTP/1.1 200");
    }

    @Test
    void noSnapshotMeans503() throws Exception {
        assertThat(get("/").status()).isEqualTo(503);
    }

    /** Sends raw bytes and returns the full response (the gateway closes or we read until EOF/timeout). */
    private String rawRequest(String request) throws Exception {
        try (Socket s = new Socket("127.0.0.1", h.gatewayPort)) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            // Ask the server to close after responding, so reading to EOF yields exactly one response.
            int eol = request.indexOf("\r\n");
            String withClose = request.substring(0, eol + 2) + "Connection: close\r\n" + request.substring(eol + 2);
            out.write(withClose.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            try {
                int n;
                while ((n = in.read(tmp)) > 0) {
                    buf.write(tmp, 0, n);
                }
            } catch (java.net.SocketTimeoutException | java.net.SocketException e) {
                // Server kept the connection open or reset it after responding; what we have is the answer.
                assertThat(buf.size()).as("no response before " + e).isPositive();
            }
            return buf.toString(StandardCharsets.ISO_8859_1);
        }
    }
}
