package apigw.controlplane.store;

import apigw.config.etcd.EtcdHttpClient;
import apigw.config.etcd.KeyValue;
import apigw.config.etcd.RangeResult;
import apigw.config.etcd.Txn;
import io.vertx.core.Future;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link KvStore} over the etcd HTTP/JSON client (ADR 0009). */
public final class EtcdKvStore implements KvStore {

    private static final int PAGE = 500;

    private final EtcdHttpClient etcd;

    public EtcdKvStore(EtcdHttpClient etcd) {
        this.etcd = etcd;
    }

    @Override
    public Future<Optional<KeyValue>> get(String key) {
        return etcd.get(key, 0).map(r -> r.kvs().isEmpty() ? Optional.empty() : Optional.of(r.kvs().getFirst()));
    }

    @Override
    public Future<Page> page(String prefix, String afterKey, int limit) {
        String from = afterKey == null ? prefix : afterKey + "\0";
        return etcd.rangePrefix(prefix, from, 0, limit).map(r -> new Page(r.revision(), r.kvs(), r.more()));
    }

    @Override
    public Future<All> all(String prefix) {
        return all(List.of(prefix));
    }

    @Override
    public Future<All> all(List<String> prefixes) {
        Map<String, KeyValue> out = new HashMap<>();
        String first = prefixes.getFirst();
        Future<All> f = etcd.rangePrefix(first, first, 0, PAGE).compose(r -> rest(first, r, r.revision(), out));
        for (String p : prefixes.subList(1, prefixes.size())) {
            f = f.compose(a -> etcd.rangePrefix(p, p, a.revision(), PAGE).compose(r -> rest(p, r, a.revision(), out)));
        }
        return f;
    }

    private Future<All> rest(String prefix, RangeResult r, long revision, Map<String, KeyValue> out) {
        List<KeyValue> kvs = r.kvs();
        kvs.forEach(kv -> out.put(kv.key(), kv));
        if (!r.more() || kvs.isEmpty()) {
            return Future.succeededFuture(new All(revision, out));
        }
        return etcd.rangePrefix(prefix, kvs.getLast().key() + "\0", revision, PAGE)
                .compose(next -> rest(prefix, next, revision, out));
    }

    @Override
    public Future<Txn.Result> txn(Txn txn) {
        return etcd.txn(txn);
    }
}
