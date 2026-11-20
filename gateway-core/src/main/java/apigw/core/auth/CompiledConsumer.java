package apigw.core.auth;

import apigw.core.plugin.Plugin;
import java.util.Set;

/**
 * A consumer compiled into a snapshot. {@link #overrides()} is indexed by plugin ordinal
 * ({@link apigw.core.plugin.PluginRegistry#ordinal(String)}): null = use the route's instance,
 * {@link #DISABLED} = skip that plugin for this consumer, anything else = run it instead. Lookups are a
 * single array read per chain slot, so consumer precedence costs nothing for consumers without overrides
 * (their array is null).
 */
public record CompiledConsumer(String id, String tenantId, String username, Set<String> roles,
        Attributes attributes, Plugin[] overrides) {

    /** Marker for a consumer-level {@code enabled: false}. */
    public static final Plugin DISABLED = new Plugin() {
        @Override
        public String toString() {
            return "DISABLED";
        }
    };

    public CompiledConsumer {
        roles = Set.copyOf(roles);
    }
}
