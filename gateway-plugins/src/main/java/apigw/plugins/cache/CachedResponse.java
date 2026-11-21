package apigw.plugins.cache;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import java.nio.charset.StandardCharsets;

/**
 * One stored response. Immutable once built; the body buffer is never written after construction and
 * is served as a read-only slice. {@code key} is kept so an L2 entry can be checked against the lookup.
 */
record CachedResponse(String key, int status, String[] names, String[] values, Buffer body, long storedAtMs,
        long freshUntilMs, long staleUntilMs, String[] tags) {

    private static final int MAGIC = 0x67774331; // "gwC1"

    /** Everything but the body, known when the response head arrives. */
    record Builder(String key, int status, String[] names, String[] values, long storedAtMs, long freshUntilMs,
            long staleUntilMs, String[] tags) {
        CachedResponse build(Buffer body) {
            return new CachedResponse(key, status, names, values, body, storedAtMs, freshUntilMs, staleUntilMs,
                    tags);
        }
    }

    int weight() {
        int w = 64 + key.length() * 2 + body.length();
        for (int i = 0; i < names.length; i++) {
            w += (names[i].length() + values[i].length()) * 2 + 32;
        }
        for (String t : tags) {
            w += t.length() * 2 + 16;
        }
        return w;
    }

    MultiMap headers() {
        MultiMap h = MultiMap.caseInsensitiveMultiMap();
        for (int i = 0; i < names.length; i++) {
            h.add(names[i], values[i]);
        }
        return h;
    }

    /** Binary form for the L2 cache. */
    Buffer encode() {
        Buffer b = Buffer.buffer(weight() + 64);
        b.appendInt(MAGIC);
        str(b, key);
        b.appendInt(status).appendLong(storedAtMs).appendLong(freshUntilMs).appendLong(staleUntilMs);
        b.appendInt(tags.length);
        for (String t : tags) {
            str(b, t);
        }
        b.appendInt(names.length);
        for (int i = 0; i < names.length; i++) {
            str(b, names[i]);
            str(b, values[i]);
        }
        b.appendInt(body.length()).appendBuffer(body);
        return b;
    }

    /** Null if {@code b} is not a well-formed entry (corrupt or from another version). */
    static CachedResponse decode(Buffer b) {
        try {
            int[] pos = {0};
            if (b.getInt(0) != MAGIC) {
                return null;
            }
            pos[0] = 4;
            String key = str(b, pos);
            int status = b.getInt(pos[0]);
            long stored = b.getLong(pos[0] + 4);
            long fresh = b.getLong(pos[0] + 12);
            long stale = b.getLong(pos[0] + 20);
            pos[0] += 28;
            int nt = count(b, pos, 64);
            String[] tags = new String[nt];
            for (int i = 0; i < nt; i++) {
                tags[i] = str(b, pos);
            }
            int nh = count(b, pos, 512);
            String[] names = new String[nh];
            String[] values = new String[nh];
            for (int i = 0; i < nh; i++) {
                names[i] = str(b, pos);
                values[i] = str(b, pos);
            }
            int len = count(b, pos, Integer.MAX_VALUE);
            if (pos[0] + len != b.length()) {
                return null;
            }
            return new CachedResponse(key, status, names, values, b.getBuffer(pos[0], pos[0] + len), stored, fresh,
                    stale, tags);
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static void str(Buffer b, String s) {
        byte[] v = s.getBytes(StandardCharsets.UTF_8);
        b.appendInt(v.length).appendBytes(v);
    }

    private static int count(Buffer b, int[] pos, int max) {
        int n = b.getInt(pos[0]);
        pos[0] += 4;
        if (n < 0 || n > max || n > b.length() - pos[0]) {
            throw new IndexOutOfBoundsException("bad count");
        }
        return n;
    }

    private static String str(Buffer b, int[] pos) {
        int n = count(b, pos, 1 << 20);
        String s = b.getString(pos[0], pos[0] + n, "UTF-8");
        pos[0] += n;
        return s;
    }
}
