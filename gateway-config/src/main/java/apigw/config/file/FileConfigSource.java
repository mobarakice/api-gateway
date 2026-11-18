package apigw.config.file;

import apigw.core.snapshot.Snapshot;
import apigw.core.snapshot.SnapshotCompiler;
import apigw.core.snapshot.SnapshotHolder;
import apigw.model.ConfigDecoder;
import apigw.model.ConfigException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads a whole-config JSON file, compiles it off the event loop (Vert.x worker pool), and publishes
 * the snapshot with one atomic swap. Polls the file's modification time and size; a changed file
 * that fails to parse, validate or compile is rejected and the current snapshot keeps serving.
 */
public final class FileConfigSource {

    private static final Logger LOG = LoggerFactory.getLogger(FileConfigSource.class);
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    private final Vertx vertx;
    private final Path file;
    private final SnapshotCompiler compiler;
    private final SnapshotHolder holder;
    private final Consumer<Snapshot> onPublished;
    private Stamp loaded;
    private Stamp rejected;
    private volatile long timer = -1;

    private record Stamp(long modifiedMillis, long size) {
    }

    public FileConfigSource(Vertx vertx, Path file, SnapshotCompiler compiler, SnapshotHolder holder,
            Consumer<Snapshot> onPublished) {
        this.vertx = Objects.requireNonNull(vertx);
        this.file = Objects.requireNonNull(file);
        this.compiler = Objects.requireNonNull(compiler);
        this.holder = Objects.requireNonNull(holder);
        this.onPublished = Objects.requireNonNull(onPublished);
    }

    /** First load. Fails if the file is missing or invalid; the caller stays not-ready then. */
    public Future<Snapshot> load() {
        return vertx.executeBlocking(this::readAndCompile, false).onSuccess(this::publish);
    }

    /** Starts polling for changes. Timer fires on an event loop; reading and compiling happen on a worker. */
    public void watch(Duration period) {
        timer = vertx.setPeriodic(period.toMillis(), id -> vertx.executeBlocking(this::checkForChange, false)
                .onSuccess(s -> {
                    if (s != null) {
                        publish(s);
                    }
                }));
    }

    public void stop() {
        if (timer >= 0) {
            vertx.cancelTimer(timer);
        }
    }

    private synchronized Snapshot checkForChange() {
        Stamp now;
        try {
            now = stamp();
        } catch (IOException e) {
            LOG.warn("config file unreadable, keeping current snapshot: {}", e.toString());
            return null;
        }
        if (now.equals(loaded) || now.equals(rejected)) {
            return null;
        }
        try {
            return readAndCompile();
        } catch (ConfigException | IOException e) {
            rejected = now;
            LOG.error("config file change rejected, keeping current snapshot: {}", e.getMessage());
            return null;
        }
    }

    private synchronized Snapshot readAndCompile() throws IOException, ConfigException {
        Stamp before = stamp();
        if (before.size() > MAX_BYTES) {
            throw new IOException("config file larger than " + MAX_BYTES + " bytes");
        }
        byte[] bytes = Files.readAllBytes(file);
        Snapshot s = compiler.compile(ConfigDecoder.decode(bytes));
        loaded = before;
        rejected = null;
        return s;
    }

    private Stamp stamp() throws IOException {
        BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
        return new Stamp(a.lastModifiedTime().toMillis(), a.size());
    }

    private void publish(Snapshot s) {
        holder.publish(s);
        LOG.info("config snapshot published source=file revision={} generation={} routes={} upstreams={}",
                s.revision(), s.generation(), s.routeCount(), s.upstreams().size());
        onPublished.accept(s);
    }
}
