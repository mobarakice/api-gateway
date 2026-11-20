package apigw.core.routing;

import static org.assertj.core.api.Assertions.assertThat;

import apigw.core.plugin.PluginRegistry;
import apigw.core.snapshot.Snapshot;
import apigw.core.snapshot.SnapshotCompiler;
import apigw.model.ConfigDecoder;
import io.vertx.core.MultiMap;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouterTest {

    private static Snapshot compile(String routes) throws Exception {
        String json = "{\"schemaVersion\":1,\"settings\":{\"upstreamAllowCidrs\":[\"127.0.0.0/8\",\"::1/128\"]},"
                + "\"upstreams\":[{\"id\":\"u\",\"targets\":[{\"host\":\"127.0.0.1\",\"port\":1}]}],"
                + "\"services\":[{\"id\":\"s\",\"upstreamId\":\"u\"}],\"routes\":[" + routes + "]}";
        return new SnapshotCompiler(new PluginRegistry(List.of()))
                .compile(ConfigDecoder.decode(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String r(String id, String extra) {
        return "{\"id\":\"" + id + "\",\"serviceId\":\"s\"," + extra + "}";
    }

    private static String find(Snapshot s, String host, String method, String path, String query, MultiMap h) {
        RouteMatch m = new RouteMatch();
        return s.router().match(host, method, path, query, h, m) ? m.route().id() : null;
    }

    private static String find(Snapshot s, String method, String path) {
        return find(s, "any.host", method, path, null, MultiMap.caseInsensitiveMultiMap());
    }

    @Test
    void staticBeatsParamBeatsPrefix() throws Exception {
        Snapshot s = compile(String.join(",",
                r("static", "\"path\":\"/users/me\",\"match\":\"exact\""),
                r("param", "\"path\":\"/users/{id}\",\"match\":\"template\""),
                r("prefix", "\"path\":\"/users\""),
                r("root", "\"path\":\"/\"")));
        assertThat(find(s, "GET", "/users/me")).isEqualTo("static");
        assertThat(find(s, "GET", "/users/42")).isEqualTo("param");
        assertThat(find(s, "GET", "/users/42/orders")).isEqualTo("prefix");
        assertThat(find(s, "GET", "/users")).isEqualTo("prefix");
        assertThat(find(s, "GET", "/usersx")).isEqualTo("root");
        assertThat(find(s, "GET", "/")).isEqualTo("root");
    }

    @Test
    void templateCapturesParamsAndBacktracks() throws Exception {
        Snapshot s = compile(String.join(",",
                r("orders", "\"path\":\"/users/{id}/orders/{oid}\",\"match\":\"template\""),
                r("static", "\"path\":\"/users/admin/settings\",\"match\":\"exact\"")));
        RouteMatch m = new RouteMatch();
        assertThat(s.router().match("h", "GET", "/users/7/orders/9", null, MultiMap.caseInsensitiveMultiMap(), m))
                .isTrue();
        assertThat(m.route().paramNames()).containsExactly("id", "oid");
        assertThat(m.param(0)).isEqualTo("7");
        assertThat(m.param(1)).isEqualTo("9");
        // "admin" goes down the static branch first, fails, then backtracks into the param branch.
        assertThat(find(s, "GET", "/users/admin/orders/1")).isEqualTo("orders");
        assertThat(find(s, "GET", "/users//orders/1")).isNull();
    }

    @Test
    void exactMatchesOnlyTheExactPath() throws Exception {
        Snapshot s = compile(r("e", "\"path\":\"/a/b\",\"match\":\"exact\""));
        assertThat(find(s, "GET", "/a/b")).isEqualTo("e");
        assertThat(find(s, "GET", "/a/b/")).isNull();
        assertThat(find(s, "GET", "/a/b/c")).isNull();
        assertThat(find(s, "GET", "/a")).isNull();
    }

    @Test
    void longerPrefixWinsAndPrefixIsSegmentBounded() throws Exception {
        Snapshot s = compile(String.join(",", r("short", "\"path\":\"/api\""), r("long", "\"path\":\"/api/v2/\"")));
        assertThat(find(s, "GET", "/api/v2/x")).isEqualTo("long");
        assertThat(find(s, "GET", "/api/v2")).isEqualTo("long");
        assertThat(find(s, "GET", "/api/v1/x")).isEqualTo("short");
        assertThat(find(s, "GET", "/apiary")).isNull();
    }

    @Test
    void methodHeaderQueryPredicatesAndPriority() throws Exception {
        Snapshot s = compile(String.join(",",
                r("get", "\"path\":\"/x\",\"methods\":[\"GET\",\"HEAD\"]"),
                r("post", "\"path\":\"/x\",\"methods\":[\"POST\"]"),
                r("beta", "\"path\":\"/x\",\"methods\":[\"GET\"],\"headers\":{\"X-Beta\":\"1\"}"),
                r("q", "\"path\":\"/x\",\"methods\":[\"GET\"],\"query\":{\"v\":\"2\"},\"priority\":10")));
        MultiMap none = MultiMap.caseInsensitiveMultiMap();
        assertThat(find(s, "h", "GET", "/x", null, none)).isEqualTo("get");
        assertThat(find(s, "h", "HEAD", "/x", null, none)).isEqualTo("get");
        assertThat(find(s, "h", "POST", "/x", null, none)).isEqualTo("post");
        assertThat(find(s, "h", "DELETE", "/x", null, none)).isNull();
        assertThat(find(s, "h", "GET", "/x", null, MultiMap.caseInsensitiveMultiMap().add("x-beta", "1")))
                .isEqualTo("beta");
        assertThat(find(s, "h", "GET", "/x", "a=1&v=2", MultiMap.caseInsensitiveMultiMap().add("x-beta", "1")))
                .isEqualTo("q");
        assertThat(find(s, "h", "GET", "/x", "v=20", none)).isEqualTo("get");
    }

    @Test
    void hostsExactThenWildcardThenAny() throws Exception {
        Snapshot s = compile(String.join(",",
                r("exact", "\"path\":\"/\",\"hosts\":[\"api.example.com\"]"),
                r("wild", "\"path\":\"/\",\"hosts\":[\"*.example.com\"]"),
                r("deep", "\"path\":\"/\",\"hosts\":[\"*.eu.example.com\"]"),
                r("any", "\"path\":\"/only-any\"")));
        MultiMap h = MultiMap.caseInsensitiveMultiMap();
        assertThat(find(s, "api.example.com", "GET", "/", null, h)).isEqualTo("exact");
        assertThat(find(s, "www.example.com", "GET", "/", null, h)).isEqualTo("wild");
        assertThat(find(s, "a.eu.example.com", "GET", "/", null, h)).isEqualTo("deep");
        assertThat(find(s, "example.com", "GET", "/", null, h)).isNull();
        assertThat(find(s, "api.example.com", "GET", "/only-any", null, h)).isEqualTo("exact");
        assertThat(find(s, "other.org", "GET", "/only-any", null, h)).isEqualTo("any");
    }

    @Test
    void normalizesHosts() {
        assertThat(Router.normalizeHost("api.example.com")).isSameAs("api.example.com");
        assertThat(Router.normalizeHost("API.Example.com:8443")).isEqualTo("api.example.com");
        assertThat(Router.normalizeHost("[::1]:8080")).isEqualTo("[::1]");
        assertThat(Router.normalizeHost("evil.com@good.com")).isNull();
        assertThat(Router.normalizeHost("a b")).isNull();
        assertThat(Router.normalizeHost(":80")).isNull();
        assertThat(Router.normalizeHost("")).isNull();
    }

    @Test
    void queryPairMatching() {
        assertThat(RouteTrie.queryHas("a=1&b=2", "b", "2")).isTrue();
        assertThat(RouteTrie.queryHas("a=1&b=2", "a", "1")).isTrue();
        assertThat(RouteTrie.queryHas("ab=2", "b", "2")).isFalse();
        assertThat(RouteTrie.queryHas("b=22", "b", "2")).isFalse();
        assertThat(RouteTrie.queryHas("", "b", "2")).isFalse();
    }

    @Test
    void scalesToManyRoutes() throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            b.append(i == 0 ? "" : ",")
                    .append(r("r" + i, "\"path\":\"/svc" + i + "/items/{id}\",\"match\":\"template\""));
        }
        Snapshot s = compile(b.toString());
        assertThat(find(s, "GET", "/svc9876/items/5")).isEqualTo("r9876");
        assertThat(find(s, "GET", "/svc10000/items/5")).isNull();
    }
}
