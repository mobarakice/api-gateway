package apigw.config.etcd;

import java.util.Objects;

/** A credential that never appears in {@code toString}, logs or exception messages. */
public final class Password {

    private final String value;

    private Password(String value) {
        this.value = Objects.requireNonNull(value);
    }

    public static Password of(String value) {
        return new Password(value);
    }

    /** Only for building the outgoing authentication request. */
    String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "Password[redacted]";
    }
}
