package apigw.core.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import apigw.core.plugin.Exchange;
import apigw.core.plugin.Outcome;
import apigw.core.plugin.Phase;
import apigw.core.plugin.Plugin;
import apigw.core.plugin.PluginConfigException;
import apigw.core.plugin.PluginFactory;
import apigw.core.plugin.PluginRegistry;
import apigw.core.plugin.RequestPlugin;
import apigw.model.ConfigDecoder;
import apigw.model.ConfigException;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SnapshotCompilerTest {

    record Tagged(String tag) implements RequestPlugin {
        @Override
        public Outcome onRequest(Exchange exchange) {
            return Outcome.CONTINUE;
        }
    }

    record Factory(String name, Phase phase, int priority) implements PluginFactory {
        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public Set<Phase> phases() {
            return Set.of(phase);
        }

        @Override
        public String schemaResource() {
            return "none";
        }

        @Override
        public Plugin create(JsonNode config) throws PluginConfigException {
            if (!config.path("tag").isTextual()) {
                throw new PluginConfigException("tag", "is required");
            }
            return new Tagged(name + ":" + config.get("tag").asText());
        }
    }

    private static final PluginRegistry REGISTRY = new PluginRegistry(List.of(
            new Factory("auth", Phase.ACCESS, 100), new Factory("rewrite", Phase.REWRITE, 0),
            new Factory("late", Phase.BEFORE_PROXY, 0), new Factory("limit", Phase.ACCESS, 50)));

    private static Snapshot compile(String global, String service, String routes) throws ConfigException {
        String json = "{\"schemaVersion\":1,\"revision\":3,"
                + "\"upstreams\":[{\"id\":\"u\",\"targets\":[{\"host\":\"::1\",\"port\":81}]}],"
                + "\"services\":[{\"id\":\"s\",\"upstreamId\":\"u\",\"timeouts\":{\"connectMs\":111},\"plugins\":["
                + service + "]}],\"routes\":[" + routes + "],\"globalPlugins\":[" + global + "]}";
        return new SnapshotCompiler(REGISTRY).compile(ConfigDecoder.decode(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> chain(CompiledRoute r) {
        return Arrays.stream(r.requestChain()).map(p -> ((Tagged) p).tag()).toList();
    }

    private static CompiledRoute only(Snapshot s, String path) {
        apigw.core.routing.RouteMatch m = new apigw.core.routing.RouteMatch();
        assertThat(s.router().match("h", "GET", path, null, io.vertx.core.MultiMap.caseInsensitiveMultiMap(), m))
                .isTrue();
        return m.route();
    }

    @Test
    void routeOverridesServiceOverridesGlobalAndPhasesOrder() throws Exception {
        Snapshot s = compile(
                "{\"name\":\"auth\",\"config\":{\"tag\":\"g\"}},{\"name\":\"late\",\"config\":{\"tag\":\"g\"}},"
                        + "{\"name\":\"limit\",\"config\":{\"tag\":\"g\"}}",
                "{\"name\":\"auth\",\"config\":{\"tag\":\"s\"}}",
                "{\"id\":\"a\",\"path\":\"/a\",\"serviceId\":\"s\",\"plugins\":["
                        + "{\"name\":\"rewrite\",\"config\":{\"tag\":\"r\"}},"
                        + "{\"name\":\"limit\",\"enabled\":false}]},"
                        + "{\"id\":\"b\",\"path\":\"/b\",\"serviceId\":\"s\",\"timeouts\":{\"idleMs\":5}}");
        assertThat(chain(only(s, "/a"))).containsExactly("rewrite:r", "auth:s", "late:g");
        assertThat(chain(only(s, "/b"))).containsExactly("auth:s", "limit:g", "late:g");
        CompiledRoute b = only(s, "/b");
        assertThat(b.timeouts()).isEqualTo(new ResolvedTimeouts(111, 30_000, 5, 0));
        assertThat(b.headerFilter()).isEmpty();
        assertThat(b.logPlugins()).isEmpty();
        assertThat(b.upstream().targets().getFirst().authority()).isEqualTo("[::1]:81");
        assertThat(s.revision()).isEqualTo(3);
        // Identical config at the same scope compiles to one shared instance.
        assertThat(only(s, "/a").requestChain()[1]).isSameAs(only(s, "/b").requestChain()[0]);
    }

    @Test
    void routeWithoutPluginsHasEmptyChains() throws Exception {
        CompiledRoute r = only(compile("", "", "{\"id\":\"a\",\"path\":\"/\",\"serviceId\":\"s\"}"), "/");
        assertThat(r.requestChain()).isEmpty();
    }

    @Test
    void reportsUnknownPluginsAndPluginConfigErrorsWithPaths() {
        assertThatThrownBy(() -> compile("{\"name\":\"nope\"}", "{\"name\":\"auth\",\"config\":{}}",
                "{\"id\":\"a\",\"path\":\"/\",\"serviceId\":\"s\"}"))
                .isInstanceOfSatisfying(ConfigException.class, e -> assertThat(e.errors()).extracting(Object::toString)
                        .containsExactlyInAnyOrder("globalPlugins[0].name: unknown plugin 'nope'",
                                "services[0].plugins[0].config.tag: is required"));
    }

    @Test
    void holderPublishesAtomically() throws Exception {
        SnapshotHolder holder = new SnapshotHolder();
        assertThat(holder.current()).isNull();
        Snapshot s = compile("", "", "{\"id\":\"a\",\"path\":\"/\",\"serviceId\":\"s\"}");
        holder.publish(s);
        assertThat(holder.current()).isSameAs(s);
    }
}
