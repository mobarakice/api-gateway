package apigw.controlplane.changes;

import apigw.config.etcd.KeyValue;
import apigw.config.worker.EtcdSnapshotBuilder;
import apigw.core.plugin.PluginServices;
import apigw.core.plugin.ValidationOnly;
import apigw.core.snapshot.Snapshot;
import apigw.core.snapshot.SnapshotCompiler;
import apigw.model.ConfigError;
import apigw.model.ConfigException;
import apigw.model.GatewayConfig;
import apigw.plugins.BuiltinPlugins;
import apigw.plugins.cache.ResponseCache;
import apigw.plugins.jwt.OidcKeySources;
import apigw.plugins.ratelimit.RateLimitStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Validates a whole candidate keyspace exactly as a gateway would: {@link EtcdSnapshotBuilder} decodes
 * it, {@link SnapshotCompiler} with the built-in plugin registry validates and compiles it (ADR 0020).
 * Plugin services are inert: no identity-provider fetches, no Redis, no gateway-local files
 * ({@link ValidationOnly}). Compiling can take a while for large configs, so it runs on the worker pool.
 */
public final class DryRun {

    private final Vertx vertx;
    private final SnapshotCompiler compiler;

    public DryRun(Vertx vertx, Clock clock) {
        this.vertx = vertx;
        PluginServices services = new PluginServices(Map.of(
                ValidationOnly.class, ValidationOnly.INSTANCE,
                OidcKeySources.class, OidcKeySources.validationOnly(vertx),
                RateLimitStore.class, new RateLimitStore(1_000, null),
                ResponseCache.class, new ResponseCache(1L << 20, null)));
        this.compiler = new SnapshotCompiler(BuiltinPlugins.registry(), services, clock);
    }

    /** Every problem with {@code state} (absolute keys under {@code prefix}); empty when valid. */
    public Future<List<ConfigError>> check(String prefix, Collection<KeyValue> state, long revision) {
        return vertx.executeBlocking(() -> {
            GatewayConfig config;
            try {
                config = EtcdSnapshotBuilder.build(prefix, state, revision);
            } catch (ConfigException e) {
                return e.errors();
            }
            try {
                Snapshot s = compiler.compile(config);
                compiler.services().published(s.resources());
                return List.<ConfigError>of();
            } catch (ConfigException e) {
                return EtcdSnapshotBuilder.toKeyPaths(e.errors(), config);
            }
        }, false);
    }
}
