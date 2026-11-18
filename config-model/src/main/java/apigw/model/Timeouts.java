package apigw.model;

/**
 * Per-hop timeouts in milliseconds. A {@code null} field is unset and inherits (route → service →
 * settings). After {@link #orElse} against {@link #DEFAULTS} every field is set; {@code 0} disables
 * {@code totalMs}.
 *
 * @param connectMs   TCP + TLS connect to the upstream target
 * @param firstByteMs request sent until response headers arrive
 * @param idleMs      longest gap without bytes in either direction once streaming
 * @param totalMs     whole exchange, 0 = no limit (long-lived streams)
 */
public record Timeouts(Integer connectMs, Integer firstByteMs, Integer idleMs, Integer totalMs) {

    public static final Timeouts DEFAULTS = new Timeouts(2_000, 30_000, 60_000, 0);
    public static final Timeouts UNSET = new Timeouts(null, null, null, null);

    /** Fields of this, falling back to {@code parent} where unset. */
    public Timeouts orElse(Timeouts parent) {
        return new Timeouts(
                connectMs != null ? connectMs : parent.connectMs,
                firstByteMs != null ? firstByteMs : parent.firstByteMs,
                idleMs != null ? idleMs : parent.idleMs,
                totalMs != null ? totalMs : parent.totalMs);
    }
}
