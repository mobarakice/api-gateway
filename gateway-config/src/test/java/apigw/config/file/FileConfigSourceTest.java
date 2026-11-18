package apigw.config.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import apigw.core.plugin.PluginRegistry;
import apigw.core.snapshot.Snapshot;
import apigw.core.snapshot.SnapshotCompiler;
import apigw.core.snapshot.SnapshotHolder;
import io.vertx.core.Vertx;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileConfigSourceTest {

    private static String config(long revision) {
        return "{\"schemaVersion\":1,\"revision\":" + revision + ",\"upstreams\":[{\"id\":\"u\",\"targets\":"
                + "[{\"host\":\"127.0.0.1\",\"port\":9}]}],\"services\":[{\"id\":\"s\",\"upstreamId\":\"u\"}],"
                + "\"routes\":[{\"id\":\"r\",\"path\":\"/\",\"serviceId\":\"s\"}]}";
    }

    private static void write(Path f, String content, long mtimeSeconds) throws Exception {
        Files.writeString(f, content);
        Files.setLastModifiedTime(f, FileTime.from(mtimeSeconds, TimeUnit.SECONDS));
    }

    @Test
    void loadsReloadsAndKeepsServingOnInvalidChange(@TempDir Path dir) throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            Path f = dir.resolve("gateway.json");
            write(f, config(1), 1_000);
            SnapshotHolder holder = new SnapshotHolder();
            List<Snapshot> published = new CopyOnWriteArrayList<>();
            FileConfigSource src = new FileConfigSource(vertx, f, new SnapshotCompiler(new PluginRegistry(List.of())),
                    holder, published::add);
            src.load().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertThat(holder.current().revision()).isEqualTo(1);
            src.watch(Duration.ofMillis(50));

            write(f, config(2), 2_000);
            await().atMost(Duration.ofSeconds(5)).until(() -> holder.current().revision() == 2);

            // Invalid change: rejected, revision 2 keeps serving, nothing published.
            write(f, config(3).replace("\"serviceId\":\"s\"", "\"serviceId\":\"missing\""), 3_000);
            Thread.sleep(400);
            assertThat(holder.current().revision()).isEqualTo(2);
            assertThat(published).hasSize(2);

            write(f, config(4), 4_000);
            await().atMost(Duration.ofSeconds(5)).until(() -> holder.current().revision() == 4);
            src.stop();
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void initialLoadFailsOnInvalidFile(@TempDir Path dir) throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            Path f = dir.resolve("bad.json");
            Files.writeString(f, "{\"schemaVersion\":1,\"routes\":[{\"id\":\"r\"}]}");
            SnapshotHolder holder = new SnapshotHolder();
            FileConfigSource src = new FileConfigSource(vertx, f, new SnapshotCompiler(new PluginRegistry(List.of())),
                    holder, s -> { });
            var result = src.load().toCompletionStage().toCompletableFuture();
            assertThat(result).failsWithin(Duration.ofSeconds(10));
            assertThat(holder.current()).isNull();
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
