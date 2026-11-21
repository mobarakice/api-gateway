package apigw.controlplane.audit;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Appends audit lines to a file (JSON Lines) through a Vert.x async file, opened in append mode.
 * Writes are ordered; a write failure is logged (the event is still in the log pipeline).
 */
public final class FileAuditSink implements AuditSink {

    private static final Logger LOG = LoggerFactory.getLogger(FileAuditSink.class);

    private final AsyncFile file;

    private FileAuditSink(AsyncFile file) {
        this.file = file;
    }

    public static Future<FileAuditSink> open(Vertx vertx, Path path) {
        return vertx.fileSystem().open(path.toString(), new OpenOptions().setAppend(true).setCreate(true)
                .setWrite(true).setRead(false)).map(FileAuditSink::new);
    }

    @Override
    public void append(String jsonLine) {
        file.write(Buffer.buffer(jsonLine).appendString("\n"))
                .onFailure(e -> LOG.error("audit sink write failed: {}", e.getClass().getSimpleName()));
    }

    public Future<Void> close() {
        return file.close();
    }
}
