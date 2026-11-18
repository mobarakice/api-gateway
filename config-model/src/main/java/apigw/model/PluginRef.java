package apigw.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A plugin attached at some scope (global, service, route). {@code config} is validated and compiled
 * by the plugin's factory when a snapshot is built.
 */
public record PluginRef(String name, boolean enabled, JsonNode config) {
}
