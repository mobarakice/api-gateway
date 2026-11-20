package apigw.core.plugin;

import java.util.Map;
import java.util.Optional;

/**
 * Long-lived runtime services that plugin factories may use (e.g. the OIDC key-set cache). Owned and
 * wired by {@code gateway-app}; looked up by type token, no reflection or discovery.
 */
public final class PluginServices {

    public static final PluginServices NONE = new PluginServices(Map.of());

    private final Map<Class<?>, Object> services;

    public PluginServices(Map<Class<?>, Object> services) {
        this.services = Map.copyOf(services);
    }

    public <T> Optional<T> find(Class<T> type) {
        return Optional.ofNullable(services.get(type)).map(type::cast);
    }

    /** Called with the snapshot's resources after it was published (lets services drop unused state). */
    public void published(java.util.Set<Object> resources) {
        for (Object s : services.values()) {
            if (s instanceof SnapshotAware a) {
                a.retainOnly(resources);
            }
        }
    }

    /** A service that keeps per-config state and can release what the live snapshot no longer uses. */
    public interface SnapshotAware {
        void retainOnly(java.util.Set<Object> inUse);
    }
}
