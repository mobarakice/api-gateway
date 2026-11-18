package apigw.core.routing;

import java.util.List;

/** HTTP methods as bits, so a route's method set is one int compare. Unknown methods get no bit. */
public final class MethodBits {

    public static final int ANY = 0;

    private MethodBits() {
    }

    public static int of(String method) {
        return switch (method) {
            case "GET" -> 1;
            case "HEAD" -> 1 << 1;
            case "POST" -> 1 << 2;
            case "PUT" -> 1 << 3;
            case "DELETE" -> 1 << 4;
            case "PATCH" -> 1 << 5;
            case "OPTIONS" -> 1 << 6;
            case "TRACE" -> 1 << 7;
            case "CONNECT" -> 1 << 8;
            default -> 1 << 30;
        };
    }

    public static int of(List<String> methods) {
        int bits = ANY;
        for (String m : methods) {
            bits |= of(m);
        }
        return bits;
    }

    static boolean allows(int routeBits, int requestBit) {
        return routeBits == ANY || (routeBits & requestBit) != 0;
    }
}
