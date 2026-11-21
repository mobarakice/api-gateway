package apigw.config.etcd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Encoding and decoding for etcd's grpc-gateway JSON: bytes fields are base64, int64 fields are
 * decimal strings, and fields with default values (0, false, empty) are omitted. Uses the Jackson
 * tree model only, so no reflection metadata is needed for Native Image.
 */
final class EtcdJson {

    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] EMPTY = new byte[0];

    private EtcdJson() {
    }

    static String b64(String utf8) {
        return Base64.getEncoder().encodeToString(utf8.getBytes(StandardCharsets.UTF_8));
    }

    static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** The smallest key greater than every key that starts with {@code prefix} (etcd's prefix range end). */
    static byte[] prefixEnd(String prefix) {
        byte[] b = prefix.getBytes(StandardCharsets.UTF_8);
        for (int i = b.length - 1; i >= 0; i--) {
            if ((b[i] & 0xFF) != 0xFF) {
                byte[] end = Arrays.copyOf(b, i + 1);
                end[i]++;
                return end;
            }
        }
        // Empty or all-0xFF prefix: etcd's convention for "to the end of the keyspace" is a single zero byte.
        return new byte[] {0};
    }

    static ObjectNode rangeRequest(String key, byte[] rangeEnd, long revision, long limit, boolean countOnly) {
        ObjectNode n = MAPPER.createObjectNode().put("key", b64(key));
        if (rangeEnd != null) {
            n.put("range_end", b64(rangeEnd));
        }
        if (revision > 0) {
            n.put("revision", Long.toString(revision));
        }
        if (limit > 0) {
            n.put("limit", Long.toString(limit));
        }
        if (countOnly) {
            n.put("count_only", true);
        }
        return n;
    }

    static ObjectNode putRequest(String key, byte[] value, long lease) {
        ObjectNode n = MAPPER.createObjectNode().put("key", b64(key)).put("value", b64(value));
        if (lease != 0) {
            n.put("lease", Long.toString(lease));
        }
        return n;
    }

    static ObjectNode watchCreate(String key, byte[] rangeEnd, long startRevision, boolean progressNotify) {
        ObjectNode create = MAPPER.createObjectNode().put("key", b64(key));
        if (rangeEnd != null) {
            create.put("range_end", b64(rangeEnd));
        }
        if (startRevision > 0) {
            create.put("start_revision", Long.toString(startRevision));
        }
        if (progressNotify) {
            create.put("progress_notify", true);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.set("create_request", create);
        return root;
    }

    static ObjectNode txnRequest(Txn txn) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode compare = root.putArray("compare");
        for (Txn.Compare c : txn.compares()) {
            compare.addObject().put("key", b64(c.key())).put("target", "MOD").put("result", "EQUAL")
                    .put("mod_revision", Long.toString(c.modRevision()));
        }
        ArrayNode success = root.putArray("success");
        for (Txn.Op op : txn.success()) {
            switch (op) {
                case Txn.Put put -> success.addObject().set("request_put", putRequest(put.key(), put.value(), 0));
                case Txn.Delete del -> success.addObject().set("request_delete_range",
                        MAPPER.createObjectNode().put("key", b64(del.key())));
            }
        }
        return root;
    }

    static JsonNode parse(byte[] body) {
        try {
            return MAPPER.readTree(body);
        } catch (java.io.IOException e) {
            throw new EtcdException("malformed etcd response", e);
        }
    }

    static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise etcd request", e);
        }
    }

    /** Unary error body: {@code {"error":"...","code":7,"message":"..."}}. Returns null when not an error. */
    static EtcdException unaryError(JsonNode n) {
        if (n.has("code") && n.path("code").asInt(0) != 0) {
            return new EtcdException(n.path("code").asInt(), n.path("message").asText(n.path("error").asText()));
        }
        return null;
    }

    /** Streaming error frame: {@code {"error":{"grpc_code":7,"message":"..."}}}. Returns null when absent. */
    static EtcdException streamError(JsonNode n) {
        JsonNode e = n.get("error");
        if (e == null || !e.isObject()) {
            return null;
        }
        return new EtcdException(e.path("grpc_code").asInt(EtcdException.TRANSPORT), e.path("message").asText());
    }

    static long revision(JsonNode response) {
        return longField(response.path("header"), "revision");
    }

    static RangeResult range(JsonNode n) {
        JsonNode kvs = n.path("kvs");
        List<KeyValue> out = new ArrayList<>(kvs.size());
        for (JsonNode kv : kvs) {
            out.add(keyValue(kv));
        }
        return new RangeResult(revision(n), out, n.path("more").asBoolean(false), longField(n, "count"));
    }

    static WatchResponse watch(JsonNode result) {
        JsonNode events = result.path("events");
        List<WatchEvent> out = new ArrayList<>(events.size());
        for (JsonNode e : events) {
            WatchEvent.Type type = "DELETE".equals(e.path("type").asText("PUT"))
                    ? WatchEvent.Type.DELETE : WatchEvent.Type.PUT;
            out.add(new WatchEvent(type, keyValue(e.path("kv"))));
        }
        return new WatchResponse(revision(result),
                result.path("created").asBoolean(false),
                result.path("canceled").asBoolean(false),
                longField(result, "compact_revision"),
                result.path("cancel_reason").asText(""),
                out);
    }

    private static KeyValue keyValue(JsonNode kv) {
        return new KeyValue(
                new String(bytes(kv, "key"), StandardCharsets.UTF_8),
                bytes(kv, "value"),
                longField(kv, "create_revision"),
                longField(kv, "mod_revision"),
                longField(kv, "version"),
                longField(kv, "lease"));
    }

    private static byte[] bytes(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return EMPTY;
        }
        try {
            return Base64.getDecoder().decode(v.asText());
        } catch (IllegalArgumentException e) {
            throw new EtcdException("malformed base64 in etcd field " + field, e);
        }
    }

    static long longValue(JsonNode n, String field) {
        return longField(n, field);
    }

    private static long longField(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return 0;
        }
        if (v.isNumber()) {
            return v.asLong();
        }
        try {
            return Long.parseLong(v.asText());
        } catch (NumberFormatException e) {
            throw new EtcdException("malformed integer in etcd field " + field, e);
        }
    }
}
