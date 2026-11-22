package apigw.app;

import apigw.core.snapshot.Snapshot;
import apigw.core.snapshot.SnapshotHolder;
import apigw.observability.logging.LogLine;
import apigw.observability.metrics.MetricRegistry;
import apigw.plugins.cache.ResponseCache;
import apigw.plugins.redis.RedisClient;

/** Gauges over long-lived gateway state: snapshot, readiness, log queue, response cache and Redis. */
final class RuntimeGauges {

    private RuntimeGauges() {
    }

    static void register(MetricRegistry m, SnapshotHolder snapshots, Readiness readiness, ResponseCache cache,
            RedisClient redis) {
        m.gauge("gw_ready", "1 when the gateway reports ready (valid snapshot, not draining).",
                () -> readiness.reason() == null ? 1 : 0);
        m.gauge("gw_snapshot_routes", "Routes in the served snapshot.", () -> {
            Snapshot s = snapshots.current();
            return s == null ? 0 : s.routeCount();
        });
        m.gauge("gw_snapshot_revision", "Config revision of the served snapshot (0 = none).", () -> {
            Snapshot s = snapshots.current();
            return s == null ? 0 : s.revision();
        });
        m.counterFn("gw_log_dropped_total", "Log lines dropped because the log queue was full.", LogLine::dropped);
        m.gauge("gw_log_queue_size", "Log lines waiting to be written.", LogLine::queued);
        String lookups = "Response cache lookups by result (hit, stale, miss, bypass).";
        m.counterFn("gw_cache_lookups_total", lookups, () -> cache.lookups(ResponseCache.HIT), "result", "hit");
        m.counterFn("gw_cache_lookups_total", lookups, () -> cache.lookups(ResponseCache.STALE), "result", "stale");
        m.counterFn("gw_cache_lookups_total", lookups, () -> cache.lookups(ResponseCache.MISS), "result", "miss");
        m.counterFn("gw_cache_lookups_total", lookups, () -> cache.lookups(ResponseCache.BYPASS), "result", "bypass");
        m.gauge("gw_cache_l1_bytes", "Approximate bytes held by the in-process response cache.", cache::l1Bytes);
        if (redis != null) {
            String cmds = "Redis commands by result (ok, failed: unavailable, timeout, error reply).";
            m.counterFn("gw_redis_commands_total", cmds, redis::commandsOk, "result", "ok");
            m.counterFn("gw_redis_commands_total", cmds, redis::commandsFailed, "result", "failed");
            m.counterFn("gw_redis_command_seconds_total", "Total latency of answered Redis commands.",
                    () -> redis.commandsOkNanos() / 1e9);
            m.gauge("gw_redis_connected", "1 when the Redis connection is ready.", () -> redis.ready() ? 1 : 0);
        }
    }
}
