package apigw.core.plugin;

/**
 * A compiled, immutable plugin instance. Implement one or more of {@link RequestPlugin},
 * {@link ResponseHeadersPlugin} and {@link LogPlugin} to take part in the matching phases.
 * Instances are shared by every request on every event loop, so they must be thread-safe and
 * must never block.
 */
public interface Plugin {
}
