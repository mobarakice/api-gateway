package apigw.config.worker;

import apigw.config.etcd.EtcdClientConfig;
import apigw.config.etcd.EtcdException;
import apigw.config.etcd.EtcdHttpClient;
import apigw.config.etcd.KeyValue;
import apigw.config.etcd.RangeResult;
import apigw.config.etcd.WatchEvent;
import apigw.config.etcd.WatchResponse;
import apigw.core.snapshot.Snapshot;
import apigw.core.snapshot.SnapshotCompiler;
import apigw.core.snapshot.SnapshotHolder;
import apigw.model.ConfigError;
import apigw.model.ConfigException;
import apigw.model.GatewayConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the published snapshot in step with etcd without ever stopping traffic (invariant 6).
 *
 * <ol>
 *   <li>Boot: restore the last-known-good snapshot if one verifies, so the gateway can serve while
 *       etcd is unreachable.</li>
 *   <li>Sync: read every config subtree at one revision R (paged), build, validate, compile, publish
 *       with one atomic swap, persist LKG.</li>
 *   <li>Watch from R+1 with progress notifications. Events are whole revisions; they are collected
 *       for a debounce window (100 ms, at most 1 s) and applied to a working copy together.</li>
 *   <li>Watch ends: compaction ({@code OUT_OF_RANGE}) or etcd moved backwards: full resync. Anything
 *       else: resume from the last seen revision after exponential backoff with jitter.</li>
 * </ol>
 *
 * <p>A state that fails to build, validate or compile is never applied, not even partially; the
 * previous snapshot keeps serving and the error is reported in the status, logs and heartbeat.
 *
 * <p>Deploy as a {@code WORKER} verticle: every callback runs on that one worker context, so state
 * needs no locks, and compilation and LKG disk I/O never run on a traffic event loop.
 */
public final class ConfigWorker extends VerticleBase {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigWorker.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PAGE = 200;
    private static final int MAX_ERRORS_IN_STATUS = 10;

    /**
     * Worker settings.
     *
     * @param prefix      config prefix ending in '/', e.g. {@code /gw/prod/}
     * @param lkgDir      directory for the last-known-good file; empty disables LKG
     * @param gatewayId   this gateway's ID for {@code nodes/<gatewayId>}
     * @param version     build version reported in the heartbeat
     */
    public record Settings(EtcdClientConfig etcd, String prefix, Optional<Path> lkgDir, String gatewayId,
            String version, Duration debounce, Duration maxDebounce, Duration backoffBase, Duration backoffMax,
            long heartbeatTtlSeconds) {

        public Settings {
            Objects.requireNonNull(etcd);
            if (!prefix.startsWith("/") || !prefix.endsWith("/")) {
                throw new IllegalArgumentException("etcd prefix must start and end with '/'");
            }
            if (gatewayId.isBlank() || gatewayId.contains("/")) {
                throw new IllegalArgumentException("gateway id must be non-empty and contain no '/'");
            }
        }

        public static Settings defaults(EtcdClientConfig etcd, String prefix, Optional<Path> lkgDir,
                String gatewayId, String version) {
            return new Settings(etcd, prefix, lkgDir, gatewayId, version, Duration.ofMillis(100),
                    Duration.ofSeconds(1), Duration.ofMillis(200), Duration.ofSeconds(10), 15);
        }
    }

    private final Settings settings;
    private final SnapshotCompiler compiler;
    private final SnapshotHolder holder;
    private final Consumer<Snapshot> onPublished;
    private final Backoff backoff;
    private final Optional<LkgStore> lkg;
    private final AtomicReference<WorkerStatus> status = new AtomicReference<>(WorkerStatus.initial());

    private EtcdHttpClient client;
    private NodeHeartbeat heartbeat;
    private Map<String, KeyValue> working = new HashMap<>();
    /** Revision the working copy reflects. */
    private volatile long revision;
    /** Revision of the published snapshot (0 = none). */
    private volatile long applied;
    private String source = "none";
    /** Last revision seen on the watch stream (events or progress); resume point. */
    private volatile long watchRevision;
    private final List<WatchEvent> pending = new ArrayList<>();
    private volatile long pendingRevision;
    private volatile long debounceTimer = -1;
    private volatile long firstPendingNanos;
    private EtcdHttpClient.Watch watch;
    private int watchGeneration;
    private int attempt;
    private volatile boolean stopped;
    private String configError;
    private String etcdError;

    public ConfigWorker(Settings settings, SnapshotCompiler compiler, SnapshotHolder holder,
            Consumer<Snapshot> onPublished) {
        this.settings = settings;
        this.compiler = compiler;
        this.holder = holder;
        this.onPublished = onPublished;
        this.backoff = new Backoff(settings.backoffBase(), settings.backoffMax());
        this.lkg = settings.lkgDir().map(d -> new LkgStore(d, settings.prefix()));
    }

    /** Current status; safe to call from any thread. */
    public WorkerStatus status() {
        return status.get();
    }

    @Override
    public Future<?> start() {
        client = EtcdHttpClient.create(vertx, settings.etcd());
        heartbeat = new NodeHeartbeat(vertx, client, settings.prefix() + "nodes/" + settings.gatewayId(),
                settings.heartbeatTtlSeconds(), this::onEtcdCall);
        restoreLkg();
        heartbeat.start();
        sync();
        // Start never waits for etcd: the gateway serves LKG (or 503) while the worker retries.
        return Future.succeededFuture();
    }

    @Override
    public Future<?> stop() {
        stopped = true;
        watchGeneration++;
        cancelDebounce();
        if (watch != null) {
            watch.cancel();
        }
        return heartbeat.stop().onComplete(ar -> client.close());
    }

    // ---- boot ----------------------------------------------------------------------------------

    private void restoreLkg() {
        if (lkg.isEmpty()) {
            return;
        }
        Optional<LkgStore.Lkg> stored = lkg.get().load();
        if (stored.isEmpty()) {
            return;
        }
        LkgStore.Lkg l = stored.get();
        Map<String, KeyValue> kvs = new HashMap<>();
        l.kvs().forEach(kv -> kvs.put(kv.key(), kv));
        try {
            Snapshot s = compile(kvs.values(), l.revision());
            working = kvs;
            revision = l.revision();
            publish(s, "lkg");
            LOG.info("restored last-known-good snapshot revision={} savedAt={} file={}", l.revision(), l.savedAt(),
                    lkg.get().file());
        } catch (ConfigException e) {
            // The LKG was valid for the build that wrote it; a newer build may reject it. Never serve it then.
            LOG.warn("last-known-good snapshot rejected by this build, ignoring revision={} errors={}",
                    l.revision(), summary(e.errors()));
        }
    }

    // ---- full sync -----------------------------------------------------------------------------

    private void sync() {
        if (stopped) {
            return;
        }
        update(WorkerStatus.State.SYNCING);
        loadAll().onComplete(ar -> {
            if (stopped) {
                return;
            }
            if (ar.failed()) {
                retry("sync", ar.cause(), this::sync);
                return;
            }
            Loaded loaded = ar.result();
            attempt = 0;
            etcdError = null;
            working = loaded.kvs();
            revision = loaded.revision();
            watchRevision = loaded.revision();
            LOG.info("etcd sync complete revision={} keys={}", loaded.revision(), loaded.kvs().size());
            applyWorkingCopy("sync");
            startWatch();
        });
    }

    private record Loaded(long revision, Map<String, KeyValue> kvs) {
    }

    private Future<Loaded> loadAll() {
        return client.countPrefix(settings.prefix()).compose(count -> {
            long rev = count.revision();
            Map<String, KeyValue> out = new HashMap<>();
            Future<Void> chain = Future.succeededFuture();
            for (String sub : EtcdSnapshotBuilder.CONFIG_SUBTREES) {
                String p = settings.prefix() + sub;
                chain = chain.compose(v -> page(p, p, rev, out));
            }
            return chain.map(v -> new Loaded(rev, out));
        });
    }

    private Future<Void> page(String prefix, String from, long rev, Map<String, KeyValue> out) {
        return client.rangePrefix(prefix, from, rev, PAGE).compose((RangeResult r) -> {
            for (KeyValue kv : r.kvs()) {
                out.put(kv.key(), kv);
            }
            if (r.more() && !r.kvs().isEmpty()) {
                return page(prefix, r.kvs().getLast().key() + "\0", rev, out);
            }
            return Future.succeededFuture();
        });
    }

    // ---- watch ---------------------------------------------------------------------------------

    private void startWatch() {
        if (stopped) {
            return;
        }
        long from = watchRevision + 1;
        int gen = ++watchGeneration;
        // Callbacks of a superseded watch (canceled for a resync) are ignored.
        watch = client.watchPrefix(settings.prefix(), from, true, r -> {
            if (gen == watchGeneration) {
                onWatch(r);
            }
        }, cause -> {
            if (gen == watchGeneration) {
                onWatchEnd(cause);
            }
        });
    }

    private void onWatch(WatchResponse r) {
        if (stopped) {
            return;
        }
        if (r.created()) {
            attempt = 0;
            etcdError = null;
            if (r.revision() < revision) {
                // The store is behind what we applied: etcd was restored or replaced. Adopt its truth.
                LOG.warn("etcd revision moved backwards (have={} etcd={}), resyncing", revision, r.revision());
                restartWith(this::sync);
                return;
            }
            update(stateAfterSuccess());
            return;
        }
        if (r.revision() > watchRevision) {
            watchRevision = r.revision();
        }
        boolean relevant = false;
        for (WatchEvent e : r.events()) {
            if (EtcdSnapshotBuilder.isConfigKey(settings.prefix(), e.kv().key())) {
                pending.add(e);
                relevant = true;
            }
        }
        if (relevant) {
            pendingRevision = r.revision();
            armDebounce();
        }
    }

    private void onWatchEnd(Throwable cause) {
        if (stopped) {
            return;
        }
        watch = null;
        flushPending();
        if (cause instanceof EtcdException ee && ee.code() == EtcdException.OUT_OF_RANGE) {
            LOG.warn("etcd watch compacted at revision {}, resyncing", watchRevision);
            sync();
            return;
        }
        retry("watch", cause, this::startWatch);
    }

    /** Cancels the watch and runs {@code next} instead of the normal end handling. */
    private void restartWith(Runnable next) {
        EtcdHttpClient.Watch w = watch;
        watch = null;
        watchGeneration++;
        if (w != null) {
            w.cancel();
        }
        next.run();
    }

    /**
     * Watch liveness. A frozen or partitioned member keeps the watch's TCP connection open but silent,
     * so the stream never ends by itself. The heartbeat's calls (every TTL/3, with a request timeout)
     * probe the cluster: if one fails, or the client has rotated to another member since the watch
     * was opened (an earlier call to the watch's member failed), the watch is recycled and reopened on
     * the client's current member, resuming from the last revision seen.
     */
    private void onEtcdCall(boolean ok) {
        EtcdHttpClient.Watch w = watch;
        if (w == null || stopped || w.endpoint() == null) {
            return;
        }
        boolean moved = !w.endpoint().equals(client.currentEndpoint());
        if (!ok || moved) {
            LOG.info("recycling the etcd watch reason={}", ok ? "client moved to another member" : "etcd call failed");
            w.cancel();
        }
    }

    // ---- applying ------------------------------------------------------------------------------

    private void armDebounce() {
        if (debounceTimer >= 0) {
            long waited = System.nanoTime() - firstPendingNanos;
            if (waited < settings.maxDebounce().toNanos()) {
                vertx.cancelTimer(debounceTimer);
                debounceTimer = vertx.setTimer(settings.debounce().toMillis(), id -> flushPending());
            }
            return;
        }
        firstPendingNanos = System.nanoTime();
        debounceTimer = vertx.setTimer(settings.debounce().toMillis(), id -> flushPending());
    }

    private void cancelDebounce() {
        if (debounceTimer >= 0) {
            vertx.cancelTimer(debounceTimer);
            debounceTimer = -1;
        }
    }

    private void flushPending() {
        cancelDebounce();
        if (pending.isEmpty()) {
            return;
        }
        Map<String, KeyValue> next = new HashMap<>(working);
        for (WatchEvent e : pending) {
            if (e.type() == WatchEvent.Type.DELETE) {
                next.remove(e.kv().key());
            } else {
                next.put(e.kv().key(), e.kv());
            }
        }
        int changes = pending.size();
        pending.clear();
        working = next;
        revision = pendingRevision;
        LOG.debug("applying {} change(s) up to revision {}", changes, revision);
        applyWorkingCopy("watch");
    }

    /** Builds, validates and compiles the working copy; publishes it only if all of that succeeds. */
    private void applyWorkingCopy(String trigger) {
        if (revision == applied && holder.current() != null) {
            update(stateAfterSuccess());
            return;
        }
        Snapshot s;
        try {
            s = compile(working.values(), revision);
        } catch (ConfigException e) {
            configError = "revision " + revision + ": " + summary(e.errors());
            LOG.error("config change rejected, keeping revision {} trigger={} revision={} errors={}", applied,
                    trigger, revision, summary(e.errors()));
            update(holder.current() == null ? WorkerStatus.State.NO_CONFIG : WorkerStatus.State.INVALID_CONFIG);
            return;
        }
        configError = null;
        publish(s, "etcd");
        lkg.ifPresent(store -> {
            try {
                store.save(revision, working.values());
            } catch (IOException e) {
                // Serving is unaffected; only a restart without etcd would lose this revision.
                LOG.warn("last-known-good write failed file={} cause={}", store.file(), e.toString());
            }
        });
    }

    private Snapshot compile(java.util.Collection<KeyValue> kvs, long rev) throws ConfigException {
        GatewayConfig config = EtcdSnapshotBuilder.build(settings.prefix(), kvs, rev);
        try {
            return compiler.compile(config);
        } catch (ConfigException e) {
            throw new ConfigException(EtcdSnapshotBuilder.toKeyPaths(e.errors(), config));
        }
    }

    private void publish(Snapshot s, String from) {
        holder.publish(s);
        applied = s.revision();
        source = from;
        LOG.info("config snapshot published source={} revision={} generation={} routes={} upstreams={}", from,
                s.revision(), s.generation(), s.routeCount(), s.upstreams().size());
        update(from.equals("lkg") ? WorkerStatus.State.SERVING_STALE : WorkerStatus.State.SERVING);
        onPublished.accept(s);
    }

    // ---- failure handling and status -----------------------------------------------------------

    private void retry(String what, Throwable cause, Runnable action) {
        int n = attempt++;
        long delay = backoff.delayMillis(n);
        etcdError = what + ": " + describe(cause);
        if (n == 0 || n % 10 == 0) {
            LOG.warn("etcd unreachable, retrying op={} attempt={} inMs={} cause={}", what, n + 1, delay,
                    describe(cause));
        } else {
            LOG.debug("etcd retry op={} attempt={} inMs={} cause={}", what, n + 1, delay, describe(cause));
        }
        update(holder.current() == null ? WorkerStatus.State.NO_CONFIG : WorkerStatus.State.SERVING_STALE);
        vertx.setTimer(delay, id -> {
            if (!stopped) {
                action.run();
            }
        });
    }

    private WorkerStatus.State stateAfterSuccess() {
        if (holder.current() == null) {
            return WorkerStatus.State.NO_CONFIG;
        }
        return configError != null ? WorkerStatus.State.INVALID_CONFIG : WorkerStatus.State.SERVING;
    }

    private void update(WorkerStatus.State state) {
        WorkerStatus s = new WorkerStatus(state, applied, Math.max(revision, watchRevision), source, configError,
                etcdError, Instant.now());
        WorkerStatus prev = status.getAndSet(s);
        if (prev.state() != state) {
            LOG.info("config worker state={} appliedRevision={} source={}", state, applied, source);
        }
        if (heartbeat != null && !stopped) {
            heartbeat.publish(heartbeatValue(s));
        }
    }

    private String heartbeatValue(WorkerStatus s) {
        ObjectNode n = JSON.createObjectNode()
                .put("gatewayId", settings.gatewayId())
                .put("version", settings.version())
                .put("state", s.state().name())
                .put("appliedRevision", s.appliedRevision())
                .put("source", s.source());
        if (s.configError() != null) {
            n.put("configError", s.configError());
        }
        return n.toString();
    }

    private static String summary(List<ConfigError> errors) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(errors.size(), MAX_ERRORS_IN_STATUS); i++) {
            b.append(i == 0 ? "" : "; ").append(errors.get(i));
        }
        if (errors.size() > MAX_ERRORS_IN_STATUS) {
            b.append("; (").append(errors.size() - MAX_ERRORS_IN_STATUS).append(" more)");
        }
        return b.toString();
    }

    private static String describe(Throwable t) {
        if (t instanceof EtcdException e) {
            return "etcd code " + e.code() + ": " + e.getMessage();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
