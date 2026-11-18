package apigw.config.etcd;

/** A change to one key. For {@link Type#DELETE}, {@code kv.value()} is empty. */
public record WatchEvent(Type type, KeyValue kv) {

    /** etcd event type. */
    public enum Type { PUT, DELETE }
}
