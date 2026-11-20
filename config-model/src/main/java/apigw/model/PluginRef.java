package apigw.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A plugin attached at some scope (global, service, route). {@code config} is validated and compiled
 * by the plugin's factory when a snapshot is built. With {@code configRef}, the config comes from that
 * {@link PluginConfigSet} instead (and {@code config} is empty).
 */
public record PluginRef(String name, boolean enabled, JsonNode config, String configRef) {

    public PluginRef(String name, boolean enabled, JsonNode config) {
        this(name, enabled, config, null);
    }
}
