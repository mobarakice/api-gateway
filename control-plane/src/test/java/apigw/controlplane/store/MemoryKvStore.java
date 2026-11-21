package apigw.controlplane.store;

import apigw.config.etcd.KeyValue;
import apigw.config.etcd.Txn;
import io.vertx.core.Future;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Unit-test stand-in for etcd with its revision semantics: one global revision bumped per write
 * transaction, {@code mod_revision} per key, missing keys compare as revision 0. Integration tests
 * use real etcd (EtcdKvStore); this only keeps {@code ./gradlew build} Docker-free.
 */
public final class MemoryKvStore implements KvStore {

    private final TreeMap<String, KeyValue> data = new TreeMap<>();
    private long revision = 1;
    private java.util.function.Supplier<Future<?>> beforeNextTxn;
    private int txns;

    /**
     * Runs once, just before the next transaction is evaluated (simulates a concurrent writer); the
     * transaction waits for the returned future.
     */
    public synchronized void beforeNextTxn(java.util.function.Supplier<Future<?>> r) {
        this.beforeNextTxn = r;
    }

    public synchronized int txnCount() {
        return txns;
    }

    public synchronized long revision() {
        return revision;
    }

    /** Direct write outside any transaction (seeding, simulated foreign writers). */
    public synchronized long put(String key, String value) {
        apply(List.of(new Txn.Put(key, value.getBytes(StandardCharsets.UTF_8))));
        return revision;
    }

    public synchronized Optional<String> value(String key) {
        return Optional.ofNullable(data.get(key)).map(KeyValue::valueUtf8);
    }

    public synchronized List<String> keys(String prefix) {
        return new ArrayList<>(data.subMap(prefix, prefix + Character.MAX_VALUE).keySet());
    }

    @Override
    public synchronized Future<Optional<KeyValue>> get(String key) {
        return Future.succeededFuture(Optional.ofNullable(data.get(key)));
    }

    @Override
    public synchronized Future<Page> page(String prefix, String afterKey, int limit) {
        List<KeyValue> out = new ArrayList<>();
        boolean more = false;
        for (Map.Entry<String, KeyValue> e : data.subMap(prefix, prefix + Character.MAX_VALUE).entrySet()) {
            if (afterKey != null && e.getKey().compareTo(afterKey) <= 0) {
                continue;
            }
            if (out.size() == limit) {
                more = true;
                break;
            }
            out.add(e.getValue());
        }
        return Future.succeededFuture(new Page(revision, out, more));
    }

    @Override
    public Future<All> all(String prefix) {
        return all(List.of(prefix));
    }

    @Override
    public synchronized Future<All> all(List<String> prefixes) {
        Map<String, KeyValue> out = new HashMap<>();
        for (String p : prefixes) {
            out.putAll(data.subMap(p, p + Character.MAX_VALUE));
        }
        return Future.succeededFuture(new All(revision, out));
    }

    @Override
    public Future<Txn.Result> txn(Txn txn) {
        java.util.function.Supplier<Future<?>> hook;
        synchronized (this) {
            hook = beforeNextTxn;
            beforeNextTxn = null;
        }
        if (hook != null) {
            return hook.get().compose(v -> evaluate(txn), e -> evaluate(txn));
        }
        return evaluate(txn);
    }

    private Future<Txn.Result> evaluate(Txn txn) {
        synchronized (this) {
            txns++;
            for (Txn.Compare c : txn.compares()) {
                KeyValue kv = data.get(c.key());
                if ((kv == null ? 0 : kv.modRevision()) != c.modRevision()) {
                    return Future.succeededFuture(new Txn.Result(false, revision));
                }
            }
            if (!txn.success().isEmpty()) {
                apply(txn.success());
            }
            return Future.succeededFuture(new Txn.Result(true, revision));
        }
    }

    private void apply(List<Txn.Op> ops) {
        revision++;
        for (Txn.Op op : ops) {
            switch (op) {
                case Txn.Put p -> {
                    KeyValue old = data.get(p.key());
                    data.put(p.key(), new KeyValue(p.key(), p.value(), old == null ? revision : old.createRevision(),
                            revision, old == null ? 1 : old.version() + 1, 0));
                }
                case Txn.Delete d -> data.remove(d.key());
            }
        }
    }
}
