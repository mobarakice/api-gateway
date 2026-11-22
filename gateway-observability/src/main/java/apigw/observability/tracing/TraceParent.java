package apigw.observability.tracing;

/**
 * A parsed W3C {@code traceparent} header (Trace Context Level 1).
 *
 * @param traceId 32 lower-case hex digits, not all zero
 * @param spanId  16 lower-case hex digits, not all zero (the caller's span)
 * @param sampled the caller recorded this request
 */
public record TraceParent(String traceId, String spanId, boolean sampled) {

    private static final String ZERO_TRACE = "00000000000000000000000000000000";
    private static final String ZERO_SPAN = "0000000000000000";

    /** Parses a header value; null when absent or invalid (an invalid header starts a new trace). */
    public static TraceParent parse(String v) {
        if (v == null || v.length() < 55) {
            return null;
        }
        if (!hex(v, 0, 2) || v.charAt(2) != '-' || v.charAt(35) != '-' || v.charAt(52) != '-'
                || !hex(v, 3, 35) || !hex(v, 36, 52) || !hex(v, 53, 55)) {
            return null;
        }
        String version = v.substring(0, 2);
        if ("ff".equals(version)) {
            return null;
        }
        // Version 00 is exactly 55 characters; later versions may append fields after a '-'.
        if (v.length() > 55 && ("00".equals(version) || v.charAt(55) != '-')) {
            return null;
        }
        String traceId = v.substring(3, 35);
        String spanId = v.substring(36, 52);
        if (ZERO_TRACE.equals(traceId) || ZERO_SPAN.equals(spanId)) {
            return null;
        }
        int flags = Character.digit(v.charAt(54), 16);
        return new TraceParent(traceId, spanId, (flags & 1) == 1);
    }

    /** {@code 00-traceId-spanId-0x}. */
    public static String format(String traceId, String spanId, boolean sampled) {
        return "00-" + traceId + "-" + spanId + (sampled ? "-01" : "-00");
    }

    private static boolean hex(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }
}
