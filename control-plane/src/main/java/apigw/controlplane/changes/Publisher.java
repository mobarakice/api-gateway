package apigw.controlplane.changes;

import apigw.config.etcd.EtcdHttpClient;
import apigw.config.etcd.KeyValue;
import apigw.config.etcd.Txn;
import apigw.config.worker.EtcdSnapshotBuilder;
import apigw.controlplane.Json;
import apigw.controlplane.api.ApiException;
import apigw.controlplane.audit.AuditLog;
import apigw.controlplane.auth.Actor;
import apigw.controlplane.store.KvStore;
import apigw.model.ConfigError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validation, diff, publish and rollback (ADR 0020).
 *
 * <p>Publish reads the whole configuration at one revision, checks every op's base revision, applies
 * the ops in memory, validates the result with the gateway's compiler, then commits everything in
 * <b>one</b> etcd transaction: the ops, the history entry, the history head, the change set's deletion
 * and the audit event. The transaction compares {@code mod_revision} of every touched key, of the
 * change set, and of {@code history/head}. The head compare serialises publishes, so a concurrent
 * publish that changed something this one did not touch (say, added a route referencing a service this
 * one deletes) makes it re-read and re-validate instead of committing a broken whole; only a change to
 * a touched key is reported as a 412 conflict.
 */
public final class Publisher {

    private static final Logger LOG = LoggerFactory.getLogger(Publisher.class);
    private static final int ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Result of a successful publish. */
    public record Published(long seq, long revision, String changeSetId) {
    }

    /** Dry-run result. */
    public record Validation(List<ConfigError> errors, List<ApiException.Conflict> conflicts) {
        public boolean valid() {
            return errors.isEmpty() && conflicts.isEmpty();
        }
    }

    /** One line of a diff. Values are redacted envelopes; null = absent. */
    public record DiffEntry(String key, ChangeSet.Action action, JsonNode before, JsonNode after, long baseRevision,
            long liveRevision) {
        public boolean stale() {
            return baseRevision != liveRevision;
        }
    }

    private final String prefix;
    private final KvStore kv;
    private final ChangeSets changeSets;
    private final AuditLog audit;
    private final DryRun dryRun;
    private final Clock clock;
    private final Limits limits;
    private final List<String> readPrefixes;

    public Publisher(String prefix, KvStore kv, ChangeSets changeSets, AuditLog audit, DryRun dryRun, Clock clock,
            Limits limits) {
        this.prefix = prefix;
        this.kv = kv;
        this.changeSets = changeSets;
        this.audit = audit;
        this.dryRun = dryRun;
        this.clock = clock;
        this.limits = limits;
        List<String> p = new ArrayList<>();
        EtcdSnapshotBuilder.CONFIG_SUBTREES.forEach(s -> p.add(prefix + s));
        p.add(headKey());
        this.readPrefixes = List.copyOf(p);
    }

    private String headKey() {
        return prefix + "history/head";
    }

    private String entryKey(long seq) {
        return prefix + "history/entries/" + String.format("%012d", seq);
    }

    // ---------------------------------------------------------------- dry run and diff

    public Future<Validation> validate(ChangeSet cs) {
        return kv.all(readPrefixes).compose(all -> {
            List<ApiException.Conflict> conflicts = conflicts(cs, all);
            return dryRun.check(prefix, applied(cs, all).values(), all.revision() + 1)
                    .map(errors -> new Validation(errors, conflicts));
        });
    }

    public Future<List<DiffEntry>> diff(ChangeSet cs) {
        return kv.all(readPrefixes).map(all -> {
            List<DiffEntry> out = new ArrayList<>();
            for (ChangeSet.Op op : cs.ops()) {
                KeyValue live = all.kvs().get(prefix + op.key());
                JsonNode before = live == null ? null : Envelopes.redact(op.key(), parseOrText(live.value()));
                out.add(new DiffEntry(op.key(), op.action(), before, Envelopes.redact(op.key(), op.value()),
                        op.baseRevision(), live == null ? 0 : live.modRevision()));
            }
            return out;
        });
    }

    private List<ApiException.Conflict> conflicts(ChangeSet cs, KvStore.All all) {
        List<ApiException.Conflict> out = new ArrayList<>();
        for (ChangeSet.Op op : cs.ops()) {
            KeyValue live = all.kvs().get(prefix + op.key());
            long rev = live == null ? 0 : live.modRevision();
            if (rev != op.baseRevision()) {
                out.add(new ApiException.Conflict(op.key(), op.baseRevision(), rev));
            }
        }
        return out;
    }

    /** The configuration keyspace with {@code cs} applied (history/head excluded). */
    private Map<String, KeyValue> applied(ChangeSet cs, KvStore.All all) {
        Map<String, KeyValue> state = new HashMap<>(all.kvs());
        state.remove(headKey());
        long rev = all.revision() + 1;
        for (ChangeSet.Op op : cs.ops()) {
            String key = prefix + op.key();
            if (op.action() == ChangeSet.Action.DELETE) {
                state.remove(key);
            } else {
                KeyValue old = state.get(key);
                state.put(key, new KeyValue(key, Json.bytes(op.value()), old == null ? rev : old.createRevision(), rev,
                        old == null ? 1 : old.version() + 1, 0));
            }
        }
        return state;
    }

    // ---------------------------------------------------------------- publish

    /** Publishes stored change set {@code id}; {@code ifMatch} (optional) pins the draft's revision. */
    public Future<Published> publish(String id, String ifMatch, Actor actor) {
        return changeSets.get(id).compose(s -> {
            if (ifMatch != null && Etags.parse(ifMatch) != s.revision()) {
                throw ApiException.conflict(List.of(new ApiException.Conflict("changesets/" + id, Etags.parse(ifMatch),
                        s.revision())));
            }
            if (s.changeSet().ops().isEmpty()) {
                throw ApiException.invalid(List.of(new ConfigError("ops", "nothing to publish")));
            }
            return attempt(s.changeSet(), s.revision(), actor, null, 1);
        });
    }

    private Future<Published> attempt(ChangeSet cs, long csRevision, Actor actor, Long rollbackOf, int n) {
        String action = rollbackOf == null ? "publish" : "rollback";
        return kv.all(readPrefixes).compose(all -> {
            List<ApiException.Conflict> conflicts = conflicts(cs, all);
            if (!conflicts.isEmpty()) {
                audit.refused(AuditLog.Event.refused(action, "conflict", actor, cs.id(), "stale base revision"));
                return Future.failedFuture(ApiException.conflict(conflicts));
            }
            return dryRun.check(prefix, applied(cs, all).values(), all.revision() + 1).compose(errors -> {
                if (!errors.isEmpty()) {
                    audit.refused(AuditLog.Event.refused(action, "invalid", actor, cs.id(), errors.size() + " errors"));
                    return Future.failedFuture(ApiException.invalid(errors));
                }
                return commit(cs, csRevision, actor, rollbackOf, all, n);
            });
        });
    }

    private Future<Published> commit(ChangeSet cs, long csRevision, Actor actor, Long rollbackOf, KvStore.All all,
            int n) {
        KeyValue head = all.kvs().get(headKey());
        long seq = head == null ? 1 : Json.parse(head.value()).path("seq").asLong() + 1;
        Instant now = clock.instant();
        List<Txn.Compare> compares = new ArrayList<>();
        List<Txn.Op> writes = new ArrayList<>();
        List<AuditLog.Change> changes = new ArrayList<>();
        ObjectNode entry = Json.MAPPER.createObjectNode().put("seq", seq).put("changeSetId", cs.id())
                .put("title", cs.title()).put("createdBy", cs.createdBy()).put("publishedBy", actor.subject())
                .put("publishedAt", now.toString());
        if (rollbackOf != null) {
            entry.put("rollbackOf", rollbackOf);
        }
        ArrayNode entryOps = entry.putArray("ops");
        for (ChangeSet.Op op : cs.ops()) {
            String key = prefix + op.key();
            KeyValue live = all.kvs().get(key);
            compares.add(new Txn.Compare(key, op.baseRevision()));
            byte[] after = op.value() == null ? null : Json.bytes(op.value());
            writes.add(after == null ? new Txn.Delete(key) : new Txn.Put(key, after));
            ObjectNode e = entryOps.addObject().put("key", op.key()).put("action", op.action().name());
            e.set("before", live == null ? null : parseOrText(live.value()));
            e.set("after", op.value());
            changes.add(new AuditLog.Change(op.key(), op.action().name(),
                    live == null ? null : Json.sha256(live.value()), Json.sha256(after)));
        }
        compares.add(new Txn.Compare(headKey(), head == null ? 0 : head.modRevision()));
        if (csRevision > 0) {
            compares.add(new Txn.Compare(changeSets.key(cs.id()), csRevision));
            writes.add(new Txn.Delete(changeSets.key(cs.id())));
        }
        writes.add(new Txn.Put(entryKey(seq), Json.bytes(entry)));
        writes.add(new Txn.Put(headKey(), Json.bytes(Json.MAPPER.createObjectNode().put("seq", seq)
                .put("changeSetId", cs.id()).put("publishedAt", now.toString()))));
        if (seq > limits.historyLimit()) {
            writes.add(new Txn.Delete(entryKey(seq - limits.historyLimit())));
        }
        AuditLog.Prepared ev = audit.prepare(AuditLog.Event.success(rollbackOf == null ? "publish" : "rollback", actor,
                rollbackOf == null ? cs.id() : "history/" + rollbackOf, cs.id(), seq, changes));
        writes.add(ev.put());
        Txn txn = new Txn(compares, writes);
        int size = EtcdHttpClient.encodedSize(txn);
        if (size > limits.maxTxnBytes() || writes.size() > limits.maxTxnOps() || compares.size() > limits.maxTxnOps()) {
            return Future.failedFuture(ApiException.invalid(List.of(new ConfigError("ops", "the publish transaction "
                    + "would be " + size + " bytes / " + writes.size() + " ops; the limits are "
                    + limits.maxTxnBytes() + " / " + limits.maxTxnOps() + ". Split the change set"))));
        }
        return kv.txn(txn).compose(r -> {
            if (r.succeeded()) {
                audit.emit(ev);
                LOG.info("published seq={} changeSet={} ops={} revision={}", seq, cs.id(), cs.ops().size(),
                        r.revision());
                return Future.succeededFuture(new Published(seq, r.revision(), cs.id()));
            }
            return whyFailed(cs, csRevision).compose(reason -> {
                if (reason != null) {
                    return Future.failedFuture(reason);
                }
                if (n >= ATTEMPTS) {
                    return Future.failedFuture(new ApiException(503, "Service unavailable",
                            "too many concurrent publishes; retry"));
                }
                return attempt(cs, csRevision, actor, rollbackOf, n + 1);
            });
        });
    }

    /** After a failed compare: a conflict to report, or null when only another publish moved the head. */
    private Future<ApiException> whyFailed(ChangeSet cs, long csRevision) {
        List<String> keys = new ArrayList<>();
        cs.ops().forEach(o -> keys.add(prefix + o.key()));
        if (csRevision > 0) {
            keys.add(changeSets.key(cs.id()));
        }
        List<Future<Optional<KeyValue>>> reads = keys.stream().map(kv::get).toList();
        return Future.all(reads).map(cf -> {
            List<ApiException.Conflict> conflicts = new ArrayList<>();
            for (int i = 0; i < cs.ops().size(); i++) {
                long rev = reads.get(i).result().map(KeyValue::modRevision).orElse(0L);
                if (rev != cs.ops().get(i).baseRevision()) {
                    ChangeSet.Op op = cs.ops().get(i);
                    conflicts.add(new ApiException.Conflict(op.key(), op.baseRevision(), rev));
                }
            }
            if (!conflicts.isEmpty()) {
                return ApiException.conflict(conflicts);
            }
            if (csRevision > 0 && reads.getLast().result().map(KeyValue::modRevision).orElse(0L) != csRevision) {
                return new ApiException(409, "Conflict", "the change set was edited or published concurrently");
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- history and rollback

    /** Published change sets, newest first (bounded by the history limit). */
    public Future<List<JsonNode>> history() {
        return kv.all(prefix + "history/entries/").map(all -> {
            List<KeyValue> kvs = new ArrayList<>(all.kvs().values());
            kvs.sort(Comparator.comparing(KeyValue::key).reversed());
            List<JsonNode> out = new ArrayList<>();
            kvs.forEach(k -> out.add(redactEntry(Json.parse(k.value()))));
            return out;
        });
    }

    public Future<JsonNode> entry(long seq) {
        return rawEntry(seq).map(Publisher::redactEntry);
    }

    private Future<JsonNode> rawEntry(long seq) {
        return kv.get(entryKey(seq)).map(o -> o.map(k -> Json.parse(k.value()))
                .orElseThrow(() -> ApiException.notFound("no such history entry (it may have aged out)")));
    }

    private static JsonNode redactEntry(JsonNode entry) {
        ObjectNode copy = entry.deepCopy();
        for (JsonNode op : copy.path("ops")) {
            String key = op.path("key").asText();
            ObjectNode o = (ObjectNode) op;
            o.set("before", Envelopes.redact(key, o.get("before")));
            o.set("after", Envelopes.redact(key, o.get("after")));
        }
        return copy;
    }

    /**
     * Publishes the inverse of history entry {@code seq} as a new transaction. Refused (409) if a later
     * publish changed any of its keys: roll those back first.
     */
    public Future<Published> rollback(long seq, Actor actor) {
        return rawEntry(seq).compose(entry -> {
            List<Future<Optional<KeyValue>>> reads = new ArrayList<>();
            for (JsonNode op : entry.path("ops")) {
                reads.add(kv.get(prefix + op.path("key").asText()));
            }
            return Future.all(reads).compose(cf -> {
                Instant now = clock.instant();
                byte[] rnd = new byte[4];
                RANDOM.nextBytes(rnd);
                List<ChangeSet.Op> ops = new ArrayList<>();
                List<String> changedLater = new ArrayList<>();
                int i = 0;
                for (JsonNode op : entry.path("ops")) {
                    String key = op.path("key").asText();
                    Optional<KeyValue> live = reads.get(i++).result();
                    JsonNode after = op.get("after");
                    boolean expectedAbsent = after == null || after.isNull();
                    boolean matches = live.isEmpty() ? expectedAbsent
                            : !expectedAbsent && Arrays.equals(Json.bytes(after), live.get().value());
                    if (!matches) {
                        changedLater.add(key);
                        continue;
                    }
                    long base = live.map(KeyValue::modRevision).orElse(0L);
                    JsonNode before = op.get("before");
                    ops.add(before == null || before.isNull()
                            ? new ChangeSet.Op(key, ChangeSet.Action.DELETE, null, base, actor.subject())
                            : new ChangeSet.Op(key, ChangeSet.Action.PUT, before, base, actor.subject()));
                }
                if (!changedLater.isEmpty()) {
                    audit.refused(AuditLog.Event.refused("rollback", "conflict", actor, "history/" + seq,
                            changedLater.size() + " keys changed later"));
                    return Future.failedFuture(new ApiException(409, "Conflict", "a later publish changed "
                            + String.join(", ", changedLater) + "; roll back the later change sets first"));
                }
                ChangeSet cs = new ChangeSet("rollback-" + seq + "-" + HexFormat.of().formatHex(rnd),
                        "Rollback of #" + seq, actor.subject(), now, now, ops);
                return attempt(cs, -1, actor, seq, 1);
            });
        });
    }

    private static JsonNode parseOrText(byte[] value) {
        try {
            return Json.parse(value);
        } catch (IllegalArgumentException e) {
            return Json.MAPPER.getNodeFactory().textNode(new String(value, java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
