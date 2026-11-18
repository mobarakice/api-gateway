package apigw.plugins;

import apigw.core.plugin.PluginRegistry;
import apigw.plugins.headers.HeadersPluginFactory;
import java.util.List;

/** Every compiled-in plugin. Adding a plugin = adding its factory here; there is no discovery. */
public final class BuiltinPlugins {

    private BuiltinPlugins() {
    }

    public static PluginRegistry registry() {
        return new PluginRegistry(List.of(new HeadersPluginFactory()));
    }
}
