package apigw.core.observe;

/** How one upstream attempt ended. */
public enum AttemptOutcome {
    /** The upstream answered with a status (any status). */
    RESPONSE("response"),
    /** Connecting failed (refused, unreachable, TLS handshake, DNS). */
    CONNECT_ERROR("connect_error"),
    /** The stream or connection was reset after connecting. */
    RESET("reset"),
    /** A connect, first-byte or total timeout fired. */
    TIMEOUT("timeout"),
    /** The local connection pool's wait queue was full. */
    POOL_FULL("pool_full"),
    /** The resolved address is not allowed (SSRF rules); nothing was sent. */
    DENIED("denied"),
    /** The circuit breaker rejected the attempt; nothing was sent. */
    CIRCUIT_OPEN("circuit_open");

    private final String label;

    AttemptOutcome(String label) {
        this.label = label;
    }

    /** Lower-case name used as a metric label and span {@code error.type}. */
    public String label() {
        return label;
    }
}
