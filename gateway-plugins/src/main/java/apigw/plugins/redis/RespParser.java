package apigw.plugins.redis;

import io.vertx.core.buffer.Buffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Incremental RESP2 parser. Bytes arrive in arbitrary chunks; complete replies go to the sink in order.
 * Bounded: bulk strings up to {@link #MAX_BULK}, arrays up to {@link #MAX_ARRAY} items, nesting up to
 * {@link #MAX_DEPTH}. A violation throws {@link ProtocolException} and the connection must be closed.
 */
final class RespParser {

    static final int MAX_BULK = 64 * 1024 * 1024;
    static final int MAX_ARRAY = 1_000_000;
    static final int MAX_DEPTH = 8;
    private static final int MAX_LINE = 64 * 1024;

    /** Unrecoverable protocol violation. */
    static final class ProtocolException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ProtocolException(String message) {
            super(message);
        }
    }

    private final Consumer<Reply> sink;
    private Buffer buf = Buffer.buffer();
    private int pos;
    /** Bytes the buffer must hold before parsing again is worthwhile (a large bulk in flight). */
    private int need;

    RespParser(Consumer<Reply> sink) {
        this.sink = sink;
    }

    void feed(Buffer chunk) {
        buf.appendBuffer(chunk);
        while (buf.length() - pos > 0 && buf.length() >= need) {
            int start = pos;
            Reply r = parse(0);
            if (r == null) {
                pos = start;
                break;
            }
            need = 0;
            sink.accept(r);
        }
        if (pos > 0 && (pos == buf.length() || pos > 64 * 1024)) {
            buf = pos == buf.length() ? Buffer.buffer() : buf.getBuffer(pos, buf.length());
            if (need > 0) {
                need -= pos;
            }
            pos = 0;
        }
    }

    /** One reply from {@link #pos}, or null if incomplete (pos is then undefined; the caller resets it). */
    private Reply parse(int depth) {
        if (depth > MAX_DEPTH) {
            throw new ProtocolException("nesting too deep");
        }
        String line = line();
        if (line == null) {
            return null;
        }
        if (line.isEmpty()) {
            throw new ProtocolException("empty line");
        }
        char type = line.charAt(0);
        String rest = line.substring(1);
        switch (type) {
            case '+':
                return new Reply.Simple(rest);
            case '-':
                return new Reply.Error(rest);
            case ':':
                return new Reply.Int(number(rest));
            case '$': {
                long n = number(rest);
                if (n == -1) {
                    return new Reply.Bulk(null);
                }
                if (n < 0 || n > MAX_BULK) {
                    throw new ProtocolException("bad bulk length");
                }
                int end = pos + (int) n;
                if (buf.length() < end + 2) {
                    need = end + 2;
                    return null;
                }
                if (buf.getByte(end) != '\r' || buf.getByte(end + 1) != '\n') {
                    throw new ProtocolException("bulk not terminated");
                }
                Buffer v = buf.getBuffer(pos, end);
                pos = end + 2;
                return new Reply.Bulk(v);
            }
            case '*': {
                long n = number(rest);
                if (n == -1) {
                    return new Reply.Array(null);
                }
                if (n < 0 || n > MAX_ARRAY) {
                    throw new ProtocolException("bad array length");
                }
                List<Reply> items = new ArrayList<>((int) Math.min(n, 1024));
                for (long i = 0; i < n; i++) {
                    Reply item = parse(depth + 1);
                    if (item == null) {
                        return null;
                    }
                    items.add(item);
                }
                return new Reply.Array(List.copyOf(items));
            }
            default:
                throw new ProtocolException("unknown reply type");
        }
    }

    private String line() {
        int limit = Math.min(buf.length(), pos + MAX_LINE);
        for (int i = pos; i + 1 < limit; i++) {
            if (buf.getByte(i) == '\r' && buf.getByte(i + 1) == '\n') {
                String s = buf.getString(pos, i, "US-ASCII");
                pos = i + 2;
                return s;
            }
        }
        if (limit == pos + MAX_LINE) {
            throw new ProtocolException("line too long");
        }
        return null;
    }

    private static long number(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new ProtocolException("bad number");
        }
    }
}
