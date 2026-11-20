package apigw.config.worker;

import apigw.config.etcd.EtcdHttpClient;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gateway's only etcd write: {@code <prefix>nodes/<gatewayId>} attached to a lease, renewed every
 * TTL/3. The value is rewritten only when it changes (applied revision, state), so a fleet's
 * heartbeats do not flood every watcher. If the lease expired (long outage) it is re-granted and the
 * value rewritten. Runs on the config worker's context.
 */
final class NodeHeartbeat {

    private static final Logger LOG = LoggerFactory.getLogger(NodeHeartbeat.class);

    private final Vertx vertx;
    private final EtcdHttpClient client;
    private final String key;
    private final long ttlSeconds;
    private final Consumer<Boolean> onEtcdCall;
    private volatile long leaseId;
    private Future<Long> granting;
    private String value;
    private boolean written;
    private volatile long timer = -1;

    /**
     * @param onEtcdCall told after every keepalive or write whether it succeeded; the worker uses it as
     *                   a liveness probe for the member its watch is on
     */
    NodeHeartbeat(Vertx vertx, EtcdHttpClient client, String key, long ttlSeconds, Consumer<Boolean> onEtcdCall) {
        this.vertx = vertx;
        this.client = client;
        this.key = key;
        this.ttlSeconds = ttlSeconds;
        this.onEtcdCall = onEtcdCall;
    }

    void start() {
        timer = vertx.setPeriodic(Math.max(1000, ttlSeconds * 1000 / 3), id -> tick());
    }

    /** Sets the value to publish; written now and again whenever the lease is re-granted. */
    void publish(String json) {
        if (json.equals(value) && written) {
            return;
        }
        value = json;
        written = false;
        write();
    }

    private void tick() {
        if (leaseId == 0) {
            write();
            return;
        }
        long id = leaseId;
        client.leaseKeepAlive(id).onComplete(ar -> {
            if (ar.failed()) {
                LOG.debug("heartbeat keepalive failed: {}", ar.cause().toString());
                onEtcdCall.accept(Boolean.FALSE);
                return;
            }
            onEtcdCall.accept(Boolean.TRUE);
            if (ar.result() == 0 && leaseId == id) {
                LOG.info("heartbeat lease expired, re-registering key={}", key);
                leaseId = 0;
                written = false;
                write();
            } else if (!written) {
                write();
            }
        });
    }

    private void write() {
        String v = value;
        if (v == null) {
            return;
        }
        lease().compose(id -> client.put(key, v.getBytes(StandardCharsets.UTF_8), id)).onComplete(ar -> {
            if (ar.succeeded()) {
                written = v.equals(value);
            } else {
                LOG.debug("heartbeat write failed: {}", ar.cause().toString());
            }
            onEtcdCall.accept(ar.succeeded());
        });
    }

    private Future<Long> lease() {
        if (leaseId != 0) {
            return Future.succeededFuture(leaseId);
        }
        if (granting == null) {
            granting = client.leaseGrant(ttlSeconds).map(l -> {
                leaseId = l.id();
                return leaseId;
            }).onComplete(ar -> granting = null);
        }
        return granting;
    }

    /** Stops renewing and revokes the lease, which deletes the key. */
    Future<Void> stop() {
        if (timer >= 0) {
            vertx.cancelTimer(timer);
            timer = -1;
        }
        if (leaseId == 0) {
            return Future.succeededFuture();
        }
        long id = leaseId;
        leaseId = 0;
        return client.leaseRevoke(id).recover(e -> {
            LOG.warn("heartbeat lease revoke failed; key expires with its lease: {}", e.toString());
            return Future.succeededFuture();
        });
    }
}
