package apigw.controlplane;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Tree-model JSON only (no data binding, so no reflection metadata for Native Image). */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private Json() {
    }

    /** Parses JSON; malformed input is an {@link IllegalArgumentException} with a generic message. */
    public static JsonNode parse(byte[] bytes) {
        try {
            JsonNode n = MAPPER.readTree(bytes);
            if (n == null) {
                throw new IllegalArgumentException("empty JSON document");
            }
            return n;
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed JSON", e);
        }
    }

    public static byte[] bytes(JsonNode n) {
        try {
            return MAPPER.writeValueAsBytes(n);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise JSON", e);
        }
    }

    public static String string(JsonNode n) {
        return new String(bytes(n), StandardCharsets.UTF_8);
    }

    /** Hex SHA-256 of {@code bytes}; null in, null out (audit before/after hashes). */
    public static String sha256(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
