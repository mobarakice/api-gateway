package apigw.core.plugin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The static, compiled-in set of plugin factories. Built once in {@code gateway-app}; no discovery. */
public final class PluginRegistry {

    private final Map<String, PluginFactory> byName;

    public PluginRegistry(List<PluginFactory> factories) {
        Map<String, PluginFactory> m = new LinkedHashMap<>();
        for (PluginFactory f : factories) {
            if (m.putIfAbsent(f.name(), f) != null) {
                throw new IllegalArgumentException("duplicate plugin name: " + f.name());
            }
            if (f.phases().isEmpty()) {
                throw new IllegalArgumentException("plugin " + f.name() + " declares no phases");
            }
        }
        this.byName = Map.copyOf(m);
    }

    public Optional<PluginFactory> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Collection<PluginFactory> all() {
        return byName.values();
    }
}
