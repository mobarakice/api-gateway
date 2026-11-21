package apigw.controlplane.changes;

import apigw.controlplane.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A draft: an ordered set of staged writes, at most one per key, each remembering the
 * {@code mod_revision} it was based on. Stored as one JSON value under {@code changesets/{id}}
 * (ADR 0020). Immutable: edits return a new instance.
 */
public record ChangeSet(String id, String title, String createdBy, Instant createdAt, Instant updatedAt,
        List<Op> ops) {

    public ChangeSet {
        ops = List.copyOf(ops);
    }

    /** What a staged op does to its key. */
    public enum Action { PUT, DELETE }

    /**
     * One staged write.
     *
     * @param key          relative key ({@code routes/a})
     * @param value        the full envelope to write; null for {@link Action#DELETE}
     * @param baseRevision the key's {@code mod_revision} when staged; 0 = the key must not exist
     */
    public record Op(String key, Action action, JsonNode value, long baseRevision, String stagedBy) {
    }

    /** This change set with {@code op} added, replacing any earlier op on the same key. */
    public ChangeSet with(Op op, Instant now) {
        List<Op> next = new ArrayList<>(ops.size() + 1);
        boolean replaced = false;
        for (Op o : ops) {
            if (o.key().equals(op.key())) {
                next.add(op);
                replaced = true;
            } else {
                next.add(o);
            }
        }
        if (!replaced) {
            next.add(op);
        }
        return new ChangeSet(id, title, createdBy, createdAt, now, next);
    }

    /** This change set without the op on {@code key}. */
    public ChangeSet without(String key, Instant now) {
        return new ChangeSet(id, title, createdBy, createdAt, now,
                ops.stream().filter(o -> !o.key().equals(key)).toList());
    }

    public boolean has(String key) {
        return ops.stream().anyMatch(o -> o.key().equals(key));
    }

    public ObjectNode toJson() {
        ObjectNode n = Json.MAPPER.createObjectNode().put("id", id).put("title", title)
                .put("createdBy", createdBy).put("createdAt", createdAt.toString())
                .put("updatedAt", updatedAt.toString());
        ArrayNode a = n.putArray("ops");
        for (Op o : ops) {
            ObjectNode on = a.addObject().put("key", o.key()).put("action", o.action().name())
                    .put("baseRevision", o.baseRevision()).put("stagedBy", o.stagedBy());
            if (o.value() != null) {
                on.set("value", o.value());
            }
        }
        return n;
    }

    public byte[] toBytes() {
        return Json.bytes(toJson());
    }

    /** Parses a stored change set; a corrupt value is an {@link IllegalStateException}. */
    public static ChangeSet parse(byte[] value) {
        JsonNode n = Json.parse(value);
        List<Op> ops = new ArrayList<>();
        for (JsonNode o : n.path("ops")) {
            Action action = Action.valueOf(o.path("action").asText());
            JsonNode v = o.get("value");
            ops.add(new Op(o.path("key").asText(), action, action == Action.PUT ? v : null,
                    o.path("baseRevision").asLong(), o.path("stagedBy").asText()));
        }
        return new ChangeSet(n.path("id").asText(), n.path("title").asText(), n.path("createdBy").asText(),
                Instant.parse(n.path("createdAt").asText()), Instant.parse(n.path("updatedAt").asText()), ops);
    }
}
