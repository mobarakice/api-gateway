package apigw.app;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Request IDs: 16 lowercase hex characters from a thread-local PRNG. Unpredictability is not a
 * security property here; IDs only correlate a client-visible error with log lines. Inbound
 * request-ID headers are not trusted.
 */
final class RequestIds {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private RequestIds() {
    }

    static String next() {
        long v = ThreadLocalRandom.current().nextLong();
        char[] out = new char[16];
        for (int i = 15; i >= 0; i--) {
            out[i] = HEX[(int) (v & 0xF)];
            v >>>= 4;
        }
        return new String(out);
    }
}
