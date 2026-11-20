package apigw.model;

import java.net.InetAddress;
import java.util.List;

/**
 * SSRF rules for upstream addresses, shared by the gateway (compile time for literal targets, connect
 * time for resolved names) and the control plane (publish time). Loopback, private, shared/CGNAT,
 * link-local (including cloud metadata 169.254.169.254 and fd00:ec2::254), unique-local, unspecified,
 * multicast, broadcast, documentation/benchmark and IPv4-mapped/NAT64 forms of those are denied unless
 * covered by an allow-list entry ({@code settings.upstreamAllowCidrs}).
 */
public final class AddressPolicy {

    private static final List<Cidr> DENIED = List.of(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
            "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15",
            "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32",
            "::/128", "::1/128", "64:ff9b::/96", "64:ff9b:1::/48", "100::/64", "2001:db8::/32",
            "fc00::/7", "fe80::/10", "ff00::/8")
            .stream().map(Cidr::parse).toList();

    private AddressPolicy() {
    }

    /** True if {@code address} (4 or 16 bytes) may be used as an upstream given the allow-list. */
    public static boolean permitted(byte[] address, List<Cidr> allow) {
        byte[] a = unmapped(address);
        for (Cidr c : allow) {
            if (c.contains(a) || c.contains(address)) {
                return true;
            }
        }
        for (Cidr c : DENIED) {
            if (c.contains(a)) {
                return false;
            }
        }
        return true;
    }

    public static boolean permitted(InetAddress address, List<Cidr> allow) {
        return permitted(address.getAddress(), allow);
    }

    /** IPv4-mapped ({@code ::ffff:a.b.c.d}) and IPv4-compatible forms are checked as IPv4. */
    private static byte[] unmapped(byte[] a) {
        if (a.length != 16) {
            return a;
        }
        for (int i = 0; i < 10; i++) {
            if (a[i] != 0) {
                return a;
            }
        }
        boolean mapped = (a[10] & 0xff) == 0xff && (a[11] & 0xff) == 0xff;
        boolean compat = a[10] == 0 && a[11] == 0 && (a[12] | a[13] | a[14]) != 0;
        if (mapped || compat) {
            return new byte[] {a[12], a[13], a[14], a[15]};
        }
        return a;
    }
}
