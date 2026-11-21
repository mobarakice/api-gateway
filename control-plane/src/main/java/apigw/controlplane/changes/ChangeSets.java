package apigw.controlplane.changes;

import apigw.config.etcd.KeyValue;
import apigw.config.etcd.Txn;
import apigw.controlplane.Json;
import apigw.controlplane.api.ApiException;
import apigw.controlplane.audit.AuditLog;
import apigw.controlplane.auth.Actor;
import apigw.controlplane.store.KvStore;
import apigw.model.ConfigError;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Drafts under {@code changesets/{id}}. Every edit is a compare-and-swap on the change set's
 * {@code mod_revision} with its audit event in the same transaction, so concurrent editors of one
 * draft never lose each other's ops.
 */
public final class ChangeSets {

    private static final int CAS_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A change set and the {@code mod_revision} of its stored value (its ETag). */
    public record Stored(ChangeSet changeSet, long revision) {
    }

    private final String prefix;
    private final KvStore kv;
    private final AuditLog audit;
    private final Clock clock;
    private final Limits limits;

    public ChangeSets(String prefix, KvStore kv, AuditLog audit, Clock clock, Limits limits) {
        this.prefix = prefix;
        this.kv = kv;
        this.audit = audit;
        this.clock = clock;
        this.limits = limits;
    }

    public String key(String id) {
        return prefix + "changesets/" + id;
    }

    public Future<Stored> create(String title, Actor actor) {
        if (title == null || title.isBlank() || title.length() > 200) {
            return Future.failedFuture(ApiException.invalid(List.of(new ConfigError("title",
                    "required: 1 to 200 characters"))));
        }
        byte[] rnd = new byte[8];
        RANDOM.nextBytes(rnd);
        String id = "cs-" + HexFormat.of().formatHex(rnd);
        Instant now = clock.instant();
        ChangeSet cs = new ChangeSet(id, title.strip(), actor.subject(), now, now, List.of());
        AuditLog.Prepared ev = audit.prepare(AuditLog.Event.success("changeset.create", actor, id, id, null,
                List.of()));
        Txn txn = new Txn(List.of(new Txn.Compare(key(id), 0)), List.of(new Txn.Put(key(id), cs.toBytes()), ev.put()));
        return kv.txn(txn).map(r -> {
            if (!r.succeeded()) {
                throw new ApiException(409, "Conflict", "change set id collision; retry");
            }
            audit.emit(ev);
            return new Stored(cs, r.revision());
        });
    }

    public Future<Stored> get(String id) {
        Envelopes.requireId(id, "change set id");
        return kv.get(key(id)).map(o -> o.map(ChangeSets::stored)
                .orElseThrow(() -> ApiException.notFound("no such change set")));
    }

    public Future<List<Stored>> list() {
        return kv.all(prefix + "changesets/").map(all -> {
            List<Stored> out = new ArrayList<>();
            all.kvs().values().forEach(k -> out.add(stored(k)));
            out.sort(Comparator.comparing((Stored s) -> s.changeSet().updatedAt()).reversed());
            return out;
        });
    }

    private static Stored stored(KeyValue kv) {
        return new Stored(ChangeSet.parse(kv.value()), kv.modRevision());
    }

    /**
     * The {@code baseRevision} for a staged op, from the object's live revision and the request's
     * {@code If-Match}. Updating or deleting an existing object requires {@code If-Match} (428);
     * a stale one is a conflict (412).
     */
    public static long base(String rel, long liveRevision, String ifMatch, boolean delete) {
        if (ifMatch == null) {
            if (liveRevision != 0 || delete) {
                throw new ApiException(428, "Precondition required",
                        "send If-Match with the object's ETag to change or delete it");
            }
            return 0;
        }
        String v = ifMatch.strip();
        if ("*".equals(v)) {
            if (liveRevision == 0) {
                throw ApiException.conflict(List.of(new ApiException.Conflict(rel, -1, 0)));
            }
            return liveRevision;
        }
        long expected = Etags.parse(v);
        if (expected != liveRevision) {
            throw ApiException.conflict(List.of(new ApiException.Conflict(rel, expected, liveRevision)));
        }
        return expected;
    }

    /**
     * Stages {@code op} in change set {@code id}, replacing an earlier op on the same key.
     * {@code value} (for puts) must already have passed {@link Envelopes#check}.
     */
    public Future<Stored> stage(String id, ChangeSet.Op op, Actor actor) {
        if (op.value() != null) {
            int size = Json.bytes(op.value()).length;
            if (size > limits.maxValueBytes()) {
                return Future.failedFuture(ApiException.invalid(List.of(new ConfigError(op.key(),
                        "object is " + size + " bytes; the limit is " + limits.maxValueBytes()))));
            }
        }
        return edit(id, actor, "changeset.stage", op.key(), cs -> {
            if (!cs.has(op.key()) && cs.ops().size() >= limits.maxOps()) {
                throw ApiException.invalid(List.of(new ConfigError("ops", "a change set holds at most "
                        + limits.maxOps() + " changes (etcd transaction limit); publish it and start another")));
            }
            ChangeSet next = cs.with(op, clock.instant());
            if (next.toBytes().length > limits.maxTxnBytes() / 3) {
                throw ApiException.invalid(List.of(new ConfigError("ops", "change set too large to publish in one "
                        + "transaction; publish it and start another")));
            }
            return next;
        });
    }

    public Future<Stored> unstage(String id, String rel, Actor actor) {
        return edit(id, actor, "changeset.unstage", rel, cs -> {
            if (!cs.has(rel)) {
                throw ApiException.notFound("no staged change for that key");
            }
            return cs.without(rel, clock.instant());
        });
    }

    /** Deletes a draft. {@code ifMatch} null = any revision. */
    public Future<Void> discard(String id, String ifMatch, Actor actor) {
        return get(id).compose(s -> {
            if (ifMatch != null && Etags.parse(ifMatch) != s.revision()) {
                throw ApiException.conflict(List.of(new ApiException.Conflict("changesets/" + id,
                        Etags.parse(ifMatch), s.revision())));
            }
            AuditLog.Prepared ev = audit.prepare(AuditLog.Event.success("changeset.discard", actor, id, id, null,
                    List.of()));
            return kv.txn(new Txn(List.of(new Txn.Compare(key(id), s.revision())),
                    List.of(new Txn.Delete(key(id)), ev.put()))).map(r -> {
                        if (!r.succeeded()) {
                            throw new ApiException(409, "Conflict", "the change set changed; reload and retry");
                        }
                        audit.emit(ev);
                        return null;
                    });
        });
    }

    private interface Edit {
        ChangeSet apply(ChangeSet cs);
    }

    private Future<Stored> edit(String id, Actor actor, String action, String rel, Edit edit) {
        return attempt(id, actor, action, rel, edit, 1);
    }

    private Future<Stored> attempt(String id, Actor actor, String action, String rel, Edit edit, int n) {
        return get(id).compose(s -> {
            ChangeSet next = edit.apply(s.changeSet());
            ChangeSet.Op op = next.ops().stream().filter(o -> o.key().equals(rel)).findFirst().orElse(null);
            AuditLog.Prepared ev = audit.prepare(AuditLog.Event.success(action, actor, rel, id, null,
                    List.of(new AuditLog.Change(rel, op == null ? "UNSTAGE" : op.action().name(), null,
                            op == null || op.value() == null ? null : Json.sha256(Json.bytes(op.value()))))));
            Txn txn = new Txn(List.of(new Txn.Compare(key(id), s.revision())),
                    List.of(new Txn.Put(key(id), next.toBytes()), ev.put()));
            return kv.txn(txn).compose(r -> {
                if (r.succeeded()) {
                    audit.emit(ev);
                    return Future.succeededFuture(new Stored(next, r.revision()));
                }
                if (n >= CAS_ATTEMPTS) {
                    return Future.failedFuture(new ApiException(409, "Conflict",
                            "the change set is being edited concurrently; retry"));
                }
                return attempt(id, actor, action, rel, edit, n + 1);
            });
        });
    }

    /** Op for an envelope that passed {@link Envelopes#check}. */
    public static ChangeSet.Op put(String rel, ObjectNode envelope, long base, Actor actor) {
        return new ChangeSet.Op(rel, ChangeSet.Action.PUT, envelope, base, actor.subject());
    }

    public static ChangeSet.Op delete(String rel, long base, Actor actor) {
        return new ChangeSet.Op(rel, ChangeSet.Action.DELETE, null, base, actor.subject());
    }
}
