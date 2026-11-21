package apigw.config.etcd;

import java.util.List;

/**
 * An etcd transaction restricted to what the control plane needs: every compare is
 * {@code mod_revision(key) == expected} (0 = the key must not exist), and the success branch puts or
 * deletes single keys. There is no failure branch: on a failed compare nothing is written.
 */
public record Txn(List<Compare> compares, List<Op> success) {

    public Txn {
        compares = List.copyOf(compares);
        success = List.copyOf(success);
    }

    /** {@code mod_revision(key) == modRevision}; 0 means "absent". */
    public record Compare(String key, long modRevision) {
    }

    /** A write in the success branch. */
    public sealed interface Op permits Put, Delete {
        String key();
    }

    /** Puts {@code value} (UTF-8 JSON or raw bytes) at {@code key}. */
    public record Put(String key, byte[] value) implements Op {
    }

    /** Deletes exactly {@code key} (no range). */
    public record Delete(String key) implements Op {
    }

    /** Outcome: {@code succeeded=false} means a compare failed and nothing was written. */
    public record Result(boolean succeeded, long revision) {
    }
}
