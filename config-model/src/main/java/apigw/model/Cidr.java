package apigw.model;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * An IPv4 or IPv6 network in CIDR notation. Parsing accepts IP literals only and never performs DNS.
 * {@link #contains(byte[])} is allocation-free.
 */
public final class Cidr {

    private final byte[] network;
    private final int prefixBits;

    private Cidr(byte[] network, int prefixBits) {
        this.network = network;
        this.prefixBits = prefixBits;
    }

    /** Parses {@code 10.0.0.0/8}, {@code 2001:db8::/32}, or a bare address (a /32 or /128). */
    public static Cidr parse(String text) {
        String t = text.trim();
        int slash = t.indexOf('/');
        String addr = slash < 0 ? t : t.substring(0, slash);
        byte[] bytes = literal(addr);
        int max = bytes.length * 8;
        int bits = max;
        if (slash >= 0) {
            try {
                bits = Integer.parseInt(t.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid prefix length in " + text, e);
            }
            if (bits < 0 || bits > max) {
                throw new IllegalArgumentException("prefix length out of range in " + text);
            }
        }
        return new Cidr(mask(bytes, bits), bits);
    }

    /** Parses an IP literal without DNS. */
    public static byte[] literal(String addr) {
        if (addr.isEmpty()) {
            throw new IllegalArgumentException("empty address");
        }
        for (int i = 0; i < addr.length(); i++) {
            char c = addr.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == ':'
                    || c == '.';
            if (!ok) {
                throw new IllegalArgumentException("not an IP literal: " + addr);
            }
        }
        if (addr.indexOf(':') < 0 && (addr.chars().filter(ch -> ch == '.').count() != 3
                || !addr.chars().allMatch(ch -> ch == '.' || (ch >= '0' && ch <= '9')))) {
            // IPv4 is digits and exactly three dots. Anything else (e.g. "dead.beef.cafe.face") is a name,
            // and handing it to InetAddress would trigger a DNS lookup.
            throw new IllegalArgumentException("not an IP literal: " + addr);
        }
        try {
            // Safe: the string is a literal, so InetAddress does not resolve it.
            return InetAddress.getByName(addr).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("not an IP literal: " + addr, e);
        }
    }

    private static byte[] mask(byte[] bytes, int bits) {
        byte[] out = Arrays.copyOf(bytes, bytes.length);
        for (int i = 0; i < out.length; i++) {
            int keep = Math.max(0, Math.min(8, bits - i * 8));
            out[i] = (byte) (out[i] & (0xFF << (8 - keep)));
        }
        return out;
    }

    /** True if {@code address} (4 or 16 bytes) is inside this network. Different families never match. */
    public boolean contains(byte[] address) {
        if (address.length != network.length) {
            return false;
        }
        int full = prefixBits / 8;
        for (int i = 0; i < full; i++) {
            if (address[i] != network[i]) {
                return false;
            }
        }
        int rest = prefixBits % 8;
        if (rest == 0) {
            return true;
        }
        int m = 0xFF << (8 - rest);
        return (address[full] & m) == (network[full] & m);
    }

    @Override
    public String toString() {
        try {
            return InetAddress.getByAddress(network).getHostAddress() + "/" + prefixBits;
        } catch (UnknownHostException e) {
            return "invalid";
        }
    }
}
