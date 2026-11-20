package apigw.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A reusable plugin config ({@code plugin-configs/{id}}). A plugin reference at any scope can use it
 * with {@code configRef} instead of an inline {@code config}; {@code plugin} must match the reference's name.
 */
public record PluginConfigSet(String id, String plugin, JsonNode config) {
}
