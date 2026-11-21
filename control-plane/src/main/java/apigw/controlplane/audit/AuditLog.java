package apigw.controlplane.audit;

import apigw.config.etcd.KeyValue;
import apigw.config.etcd.Txn;
import apigw.controlplane.Json;
import apigw.controlplane.auth.Actor;
import apigw.controlplane.store.KvStore;
import apigw.observability.logging.JsonLogging;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only audit events for every Admin API mutation (ADR 0021): who, what, before/after hashes,
 * change set, time, source IP. A successful mutation's event is written to {@code audit/} in the same
 * etcd transaction as the mutation itself, then logged (logger {@code audit}) and appended to the
 * external sink. Refused attempts (denied, conflict, invalid) are logged and sunk but never written to
 * etcd, so unauthorised callers cannot fill it. etcd keeps a bounded recent window for the console;
 * the log pipeline and sink are the archive.
 */
public final class AuditLog {

    private static final Logger LOG = LoggerFactory.getLogger(AuditLog.class);
    private static final String LOGGER = "audit";
    private static final int MAX_CHANGES = 200;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** One key touched by a mutation; hashes are SHA-256 of the stored bytes (null = absent). */
    public record Change(String key, String action, String beforeHash, String afterHash) {
    }

    /**
     * An auditable action.
     *
     * @param outcome success, denied, conflict, invalid or failed
     */
    public record Event(String action, String outcome, Actor actor, String target, String changeSetId, Long seq,
            List<Change> changes, String detail) {
        public Event {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }

        public static Event success(String action, Actor actor, String target, String changeSetId, Long seq,
                List<Change> changes) {
            return new Event(action, "success", actor, target, changeSetId, seq, changes, null);
        }

        public static Event refused(String action, String outcome, Actor actor, String target, String detail) {
            return new Event(action, outcome, actor, target, null, null, List.of(), detail);
        }
    }

    /** A rendered event, ready to be written in a transaction and emitted once it commits. */
    public record Prepared(String key, ObjectNode json) {
        public Txn.Put put() {
            return new Txn.Put(key, Json.bytes(json));
        }
    }

    private final String prefix;
    private final KvStore kv;
    private final Clock clock;
    private final Optional<AuditSink> sink;
    private final int window;

    public AuditLog(String prefix, KvStore kv, Clock clock, Optional<AuditSink> sink, int window) {
        this.prefix = prefix;
        this.kv = kv;
        this.clock = clock;
        this.sink = sink;
        this.window = window;
    }

    public Prepared prepare(Event e) {
        Instant now = clock.instant();
        long micros = now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000;
        byte[] rnd = new byte[4];
        RANDOM.nextBytes(rnd);
        String id = String.format("%016d-%s", micros, HexFormat.of().formatHex(rnd));
        ObjectNode n = Json.MAPPER.createObjectNode().put("id", id).put("at", now.toString())
                .put("action", e.action()).put("outcome", e.outcome());
        ObjectNode actor = n.putObject("actor");
        if (e.actor() != null) {
            actor.put("subject", e.actor().subject()).put("role", e.actor().role() == null ? null
                    : e.actor().role().claimName());
            n.put("sourceIp", e.actor().sourceIp()).put("requestId", e.actor().requestId());
        }
        if (e.target() != null) {
            n.put("target", e.target());
        }
        if (e.changeSetId() != null) {
            n.put("changeSetId", e.changeSetId());
        }
        if (e.seq() != null) {
            n.put("seq", e.seq());
        }
        if (!e.changes().isEmpty()) {
            ArrayNode a = n.putArray("changes");
            for (Change c : e.changes().subList(0, Math.min(MAX_CHANGES, e.changes().size()))) {
                a.addObject().put("key", c.key()).put("action", c.action()).put("beforeHash", c.beforeHash())
                        .put("afterHash", c.afterHash());
            }
            if (e.changes().size() > MAX_CHANGES) {
                n.put("changesTruncated", e.changes().size());
            }
        }
        if (e.detail() != null) {
            n.put("detail", e.detail());
        }
        return new Prepared(prefix + "audit/" + id, n);
    }

    /** Logs and sinks an event whose etcd write (if any) has committed. */
    public void emit(Prepared p) {
        String line = Json.string(p.json());
        JsonLogging.event(LOGGER, "audit", "audit", line);
        sink.ifPresent(s -> s.append(line));
    }

    /** Logs and sinks a refused attempt (not written to etcd). */
    public void refused(Event e) {
        emit(prepare(e));
    }

    /** The newest events first, from the bounded etcd window. */
    public Future<List<JsonNode>> recent(int limit, String before) {
        return kv.all(prefix + "audit/").map(all -> {
            List<KeyValue> kvs = new ArrayList<>(all.kvs().values());
            kvs.sort(Comparator.comparing(KeyValue::key).reversed());
            List<JsonNode> out = new ArrayList<>();
            for (KeyValue k : kvs) {
                String id = k.key().substring(prefix.length() + "audit/".length());
                if (before != null && id.compareTo(before) >= 0) {
                    continue;
                }
                out.add(Json.parse(k.value()));
                if (out.size() >= limit) {
                    break;
                }
            }
            return out;
        });
    }

    /** Deletes the oldest etcd events beyond the window. Returns how many were removed. */
    public Future<Integer> trim() {
        return kv.all(prefix + "audit/").compose(all -> {
            int excess = all.kvs().size() - window;
            if (excess <= 0) {
                return Future.succeededFuture(0);
            }
            List<String> keys = new ArrayList<>(all.kvs().keySet());
            keys.sort(Comparator.naturalOrder());
            List<Txn.Op> dels = new ArrayList<>();
            for (String k : keys.subList(0, Math.min(excess, 100))) {
                dels.add(new Txn.Delete(k));
            }
            return kv.txn(new Txn(List.of(), dels)).map(r -> dels.size());
        }).onFailure(e -> LOG.warn("audit window trim failed: {}", e.getMessage()));
    }
}
