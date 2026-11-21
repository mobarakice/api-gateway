package apigw.controlplane.store;

import apigw.config.etcd.KeyValue;
import apigw.config.etcd.Txn;
import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The control plane's view of etcd: absolute keys, mod revisions, and single-transaction writes.
 * {@link EtcdKvStore} is the only production implementation.
 */
public interface KvStore {

    Future<Optional<KeyValue>> get(String key);

    /**
     * Up to {@code limit} keys under {@code prefix} that sort after {@code afterKey} (null = from the
     * start), at the latest revision.
     */
    Future<Page> page(String prefix, String afterKey, int limit);

    /** Every key under {@code prefix}, read at one revision (paged internally). */
    Future<All> all(String prefix);

    /** Every key under each of {@code prefixes}, all read at the same revision. */
    Future<All> all(List<String> prefixes);

    Future<Txn.Result> txn(Txn txn);

    /** One page of a listing. */
    record Page(long revision, List<KeyValue> kvs, boolean more) {
        public Page {
            kvs = List.copyOf(kvs);
        }
    }

    /** A consistent read of a whole prefix. */
    record All(long revision, Map<String, KeyValue> kvs) {
        public All {
            kvs = Map.copyOf(kvs);
        }
    }
}
