package apigw.proxy;

import apigw.model.AddressPolicy;
import apigw.model.Cidr;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves upstream host names for the SSRF connect-time check (ADR 0015). Lookups use the platform
 * resolver (so {@code /etc/hosts} and cluster DNS behave as usual) on a small dedicated worker pool,
 * never on an event loop. Results are cached for 30 s (failures for 5 s); the cache is shared by all
 * event loops.
 *
 * <p>Every resolved address must be permitted, not just the one used: a name that resolves to a mix
 * of public and internal addresses is refused. The caller connects to the returned, checked address
 * (DNS rebinding cannot swap it between check and connect).
 */
public final class TargetResolver {

    private static final long TTL_NANOS = 30_000_000_000L;
    private static final long NEGATIVE_TTL_NANOS = 5_000_000_000L;
    private static final int MAX_ENTRIES = 10_000;

    /** Platform lookup; replaceable in tests. */
    @FunctionalInterface
    public interface Lookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    private record Entry(InetAddress[] addresses, long expires) {
    }

    /** The name resolved to (at least one) address that the SSRF rules deny. */
    public static final class DeniedAddressException extends Exception {
        private static final long serialVersionUID = 1L;

        DeniedAddressException(String host) {
            super("upstream host " + host + " resolves to a denied address");
        }
    }

    private final WorkerExecutor workers;
    private final Lookup lookup;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public TargetResolver(Vertx vertx, Lookup lookup) {
        this.workers = vertx.createSharedWorkerExecutor("gw-dns", 4);
        this.lookup = lookup;
    }

    public TargetResolver(Vertx vertx) {
        this(vertx, InetAddress::getAllByName);
    }

    /** The address to connect to, or a failed future ({@link DeniedAddressException}, {@link UnknownHostException}). */
    public Future<InetAddress> resolve(String host, List<Cidr> allow) {
        Entry e = cache.get(host);
        if (e != null && e.expires() - System.nanoTime() > 0) {
            return check(host, e.addresses(), allow);
        }
        return workers.<InetAddress[]>executeBlocking(() -> {
            try {
                InetAddress[] a = lookup.lookup(host);
                put(host, new Entry(a, System.nanoTime() + TTL_NANOS));
                return a;
            } catch (UnknownHostException ex) {
                put(host, new Entry(new InetAddress[0], System.nanoTime() + NEGATIVE_TTL_NANOS));
                throw ex;
            }
        }, false).compose(a -> check(host, a, allow));
    }

    private void put(String host, Entry e) {
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(host, e);
    }

    private static Future<InetAddress> check(String host, InetAddress[] addresses, List<Cidr> allow) {
        if (addresses.length == 0) {
            return Future.failedFuture(new UnknownHostException(host));
        }
        for (InetAddress a : addresses) {
            if (!AddressPolicy.permitted(a, allow)) {
                return Future.failedFuture(new DeniedAddressException(host));
            }
        }
        return Future.succeededFuture(addresses[0]);
    }
}
