package apigw.config.etcd;

import java.util.List;

/**
 * Result of a range read.
 *
 * @param revision store revision the read was served at
 * @param kvs      returned pairs (empty for count-only reads)
 * @param more     true when a limit cut the result short
 * @param count    total number of keys in the range
 */
public record RangeResult(long revision, List<KeyValue> kvs, boolean more, long count) {

    public RangeResult {
        kvs = List.copyOf(kvs);
    }
}
