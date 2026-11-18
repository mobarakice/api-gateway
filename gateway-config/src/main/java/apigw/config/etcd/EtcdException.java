package apigw.config.etcd;

/**
 * An error reported by etcd (gRPC status code + message) or a transport failure ({@link #TRANSPORT}).
 * Messages come from etcd or the network stack and never contain credentials.
 */
public final class EtcdException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Transport-level failure: connection refused, TLS failure, timeout, malformed response. */
    public static final int TRANSPORT = -1;
    public static final int INVALID_ARGUMENT = 3;
    public static final int PERMISSION_DENIED = 7;
    public static final int OUT_OF_RANGE = 11;
    public static final int UNAVAILABLE = 14;
    public static final int UNAUTHENTICATED = 16;

    private final int code;

    public EtcdException(int code, String message) {
        super(message);
        this.code = code;
    }

    public EtcdException(String message, Throwable cause) {
        super(message, cause);
        this.code = TRANSPORT;
    }

    /** gRPC status code, or {@link #TRANSPORT}. */
    public int code() {
        return code;
    }

    public boolean permissionDenied() {
        return code == PERMISSION_DENIED;
    }

    /** etcd reports an expired or unknown simple/JWT token this way. */
    boolean invalidToken() {
        String m = getMessage();
        return code == UNAUTHENTICATED || (m != null && m.contains("invalid auth token"));
    }
}
