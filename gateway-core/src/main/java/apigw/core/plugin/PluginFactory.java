package apigw.core.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * Describes a plugin and builds instances from config at snapshot compile time (never per request).
 */
public interface PluginFactory {

    /** Unique name used in configuration, e.g. {@code headers}. */
    String name();

    /** Semantic version of the plugin's config contract. */
    String version();

    /** Phases this plugin's instances take part in. */
    Set<Phase> phases();

    /** Order within a phase: higher runs first. Built-ins document theirs in the authoring guide. */
    int priority();

    /** Classpath resource of the plugin's JSON Schema (2020-12), shared with the control plane. */
    String schemaResource();

    /**
     * Validates {@code config} and returns an immutable compiled instance.
     *
     * @throws PluginConfigException with every problem, paths relative to {@code config}
     */
    Plugin create(JsonNode config) throws PluginConfigException;
}
