package apigw.observability.tracing;

import java.util.concurrent.ThreadLocalRandom;

/** Random, non-zero trace and span IDs as lower-case hex (W3C requires randomness, not secrecy). */
public final class TraceIds {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private TraceIds() {
    }

    public static String traceId() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long hi = r.nextLong();
        long lo = r.nextLong();
        if (hi == 0 && lo == 0) {
            lo = 1;
        }
        char[] c = new char[32];
        put(c, 0, hi);
        put(c, 16, lo);
        return new String(c);
    }

    public static String spanId() {
        long v = ThreadLocalRandom.current().nextLong();
        char[] c = new char[16];
        put(c, 0, v == 0 ? 1 : v);
        return new String(c);
    }

    private static void put(char[] c, int at, long v) {
        for (int i = 15; i >= 0; i--) {
            c[at + i] = HEX[(int) (v & 0xF)];
            v >>>= 4;
        }
    }
}
