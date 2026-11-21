package apigw.plugins.redis;

import io.vertx.core.buffer.Buffer;
import java.util.List;

/** A RESP2 reply. */
public sealed interface Reply {

    /** {@code +OK}. */
    record Simple(String value) implements Reply {
    }

    /** {@code -ERR ...}: a command error (the connection stays usable). */
    record Error(String message) implements Reply {
    }

    /** {@code :42}. */
    record Int(long value) implements Reply {
    }

    /** {@code $n}; {@code value} is null for a nil bulk string. */
    record Bulk(Buffer value) implements Reply {
    }

    /** {@code *n}; {@code items} is null for a nil array. */
    record Array(List<Reply> items) implements Reply {
    }
}
