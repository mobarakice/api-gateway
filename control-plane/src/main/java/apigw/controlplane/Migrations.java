package apigw.controlplane;

import apigw.config.etcd.KeyValue;
import apigw.config.etcd.Txn;
import apigw.controlplane.audit.AuditLog;
import apigw.controlplane.store.KvStore;
import apigw.model.GatewayConfig;
import io.vertx.core.Future;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Versioned key-schema migrations, run by the control plane at startup and recorded in
 * {@code meta/schema-version} (ADR 0020). Version 1 is the current layout; migrating from "nothing"
 * initialises an empty keyspace. A newer version than this build knows stops the control plane: an
 * older build must never write a layout it does not understand.
 */
public final class Migrations {

    private static final Logger LOG = LoggerFactory.getLogger(Migrations.class);

    private Migrations() {
    }

    /** Brings the keyspace to {@link GatewayConfig#SUPPORTED_SCHEMA_VERSION}; returns the version. */
    public static Future<Integer> run(String prefix, KvStore kv, AuditLog audit) {
        String key = prefix + "meta/schema-version";
        return kv.get(key).compose(o -> {
            Optional<Integer> current = o.map(Migrations::version);
            int target = GatewayConfig.SUPPORTED_SCHEMA_VERSION;
            if (current.isPresent()) {
                int v = current.get();
                if (v > target) {
                    return Future.failedFuture(new IllegalStateException("keyspace schema version " + v
                            + " is newer than this control plane supports (" + target + "); upgrade it"));
                }
                if (v == target) {
                    return Future.succeededFuture(v);
                }
                // Only version 1 exists so far; future migrations go here, one transaction each.
                return Future.failedFuture(new IllegalStateException("no migration from schema version " + v));
            }
            AuditLog.Prepared ev = audit.prepare(AuditLog.Event.success("migrate", null, "meta/schema-version",
                    null, null, List.of(new AuditLog.Change("meta/schema-version", "PUT", null, null))));
            Txn txn = new Txn(List.of(new Txn.Compare(key, 0)), List.of(new Txn.Put(key,
                    Integer.toString(target).getBytes(StandardCharsets.US_ASCII)), ev.put()));
            return kv.txn(txn).compose(r -> {
                if (r.succeeded()) {
                    audit.emit(ev);
                    LOG.info("initialised keyspace prefix={} schemaVersion={}", prefix, target);
                    return Future.succeededFuture(target);
                }
                // Another control-plane replica initialised it first.
                return run(prefix, kv, audit);
            });
        });
    }

    private static int version(KeyValue kv) {
        String v = new String(kv.value(), StandardCharsets.US_ASCII).strip().replace("\"", "");
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("meta/schema-version is not an integer");
        }
    }
}
