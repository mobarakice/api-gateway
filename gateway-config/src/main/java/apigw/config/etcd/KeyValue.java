package apigw.config.etcd;

import java.nio.charset.StandardCharsets;

/**
 * One etcd key-value pair. Keys are UTF-8 paths in this project; values are raw bytes.
 * The {@code value} array is owned by this record and must not be modified.
 */
public record KeyValue(String key, byte[] value, long createRevision, long modRevision, long version, long lease) {

    public String valueUtf8() {
        return new String(value, StandardCharsets.UTF_8);
    }
}
