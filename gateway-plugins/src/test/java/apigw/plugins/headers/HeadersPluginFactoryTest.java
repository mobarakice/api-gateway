package apigw.plugins.headers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import apigw.core.plugin.PluginConfigException;
import apigw.core.plugin.RequestPlugin;
import apigw.core.plugin.ResponseHeadersPlugin;
import apigw.plugins.BuiltinPlugins;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;

class HeadersPluginFactoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final apigw.core.plugin.PluginContext CTX = apigw.core.plugin.PluginContext.empty();
    private final HeadersPluginFactory factory = new HeadersPluginFactory();

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    @Test
    void compilesToTheNarrowestShape() throws Exception {
        assertThat(factory.create(json("{\"request\":{\"set\":{\"a\":\"1\"}}}"), CTX))
                .isInstanceOf(RequestPlugin.class).isNotInstanceOf(ResponseHeadersPlugin.class);
        assertThat(factory.create(json("{\"response\":{\"remove\":[\"server\"]}}"), CTX))
                .isInstanceOf(ResponseHeadersPlugin.class).isNotInstanceOf(RequestPlugin.class);
        assertThat(factory.create(json("{\"request\":{\"add\":{\"a\":\"1\"}},\"response\":{\"add\":{\"b\":\"2\"}}}"),
                CTX))
                .isInstanceOf(RequestPlugin.class).isInstanceOf(ResponseHeadersPlugin.class);
    }

    @Test
    void appliesRemoveThenSetThenAdd() {
        HeaderEdits e = new HeaderEdits(java.util.Map.of("x-set", "new"), java.util.Map.of("x-add", "2"),
                java.util.List.of("x-drop", "x-set"));
        MultiMap h = MultiMap.caseInsensitiveMultiMap().add("X-Drop", "1").add("x-set", "old").add("x-add", "1");
        e.apply(h);
        assertThat(h.contains("x-drop")).isFalse();
        assertThat(h.getAll("x-set")).containsExactly("new");
        assertThat(h.getAll("x-add")).containsExactly("1", "2");
    }

    @Test
    void rejectsUnsafeConfig() {
        assertThatThrownBy(() -> factory.create(json("""
                {"request": {"set": {"Content-Length": "5", "bad name": "x", "x-ok": "a\\r\\nb"}, "remove": ["host"]},
                 "response": {"oops": 1}, "extra": true}"""), CTX))
                .isInstanceOfSatisfying(PluginConfigException.class, e -> assertThat(e.errors())
                        .extracting(Object::toString).containsExactlyInAnyOrder(
                                "extra: unknown field",
                                "request.set.Content-Length: header 'Content-Length' cannot be edited"
                                        + " (framing/hop-by-hop)",
                                "request.set.bad name: not a valid header name",
                                "request.set.x-ok: value must be visible ASCII, space or tab (no CR/LF)",
                                "request.remove[0]: header 'host' cannot be edited (framing/hop-by-hop)",
                                "response.oops: unknown field"));
        assertThatThrownBy(() -> factory.create(json("{}"), CTX)).isInstanceOf(PluginConfigException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void isRegisteredAndShipsItsSchema() {
        assertThat(BuiltinPlugins.registry().find("headers")).containsInstanceOf(HeadersPluginFactory.class);
        assertThat(HeadersPluginFactory.class.getClassLoader().getResource(factory.schemaResource())).isNotNull();
    }
}
