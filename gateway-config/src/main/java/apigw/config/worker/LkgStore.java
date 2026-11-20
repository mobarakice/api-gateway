package apigw.config.worker;

import apigw.config.etcd.KeyValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Last-known-good store: the raw config key-values of the last applied snapshot, so a gateway that
 * restarts while etcd is unreachable serves what it served before. Written only after a snapshot
 * compiled and was published.
 *
 * <p>File: {@code <dir>/lkg-<prefix>.json} = {@code {"sha256": hex, "payload": {...}}}; the digest
 * covers the serialised payload and detects truncation or corruption (JDK SHA-256, not our own
 * crypto). Writes go to a temp file, are fsync'ed, then renamed atomically; permissions are
 * owner-only where the file system supports POSIX permissions (values can include hashed
 * credentials from M4 on). Blocking I/O: call only from the config worker's thread.
 */
public final class LkgStore {

    private static final Logger LOG = LoggerFactory.getLogger(LkgStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FORMAT = 1;

    private final Path dir;
    private final Path file;
    private final String prefix;

    /** One stored state. */
    public record Lkg(long revision, List<KeyValue> kvs, Instant savedAt) {
        public Lkg {
            kvs = List.copyOf(kvs);
        }
    }

    public LkgStore(Path dir, String prefix) {
        this.prefix = prefix;
        this.dir = dir;
        this.file = dir.resolve("lkg-" + prefix.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
    }

    public Path file() {
        return file;
    }

    public void save(long revision, Collection<KeyValue> kvs) throws IOException {
        List<KeyValue> sorted = new ArrayList<>(kvs);
        sorted.sort(Comparator.comparing(KeyValue::key));
        ObjectNode payload = MAPPER.createObjectNode()
                .put("format", FORMAT)
                .put("prefix", prefix)
                .put("revision", revision)
                .put("savedAt", Instant.now().toString());
        ArrayNode arr = payload.putArray("kvs");
        Base64.Encoder b64 = Base64.getEncoder();
        for (KeyValue kv : sorted) {
            arr.addObject().put("key", kv.key()).put("value", b64.encodeToString(kv.value()))
                    .put("createRevision", kv.createRevision()).put("modRevision", kv.modRevision())
                    .put("version", kv.version());
        }
        byte[] payloadBytes = MAPPER.writeValueAsBytes(payload);
        ObjectNode root = MAPPER.createObjectNode().put("sha256", sha256(payloadBytes));
        root.set("payload", payload);
        byte[] bytes = MAPPER.writeValueAsBytes(root);

        Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        createOwnerOnly(tmp);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void createOwnerOnly(Path p) throws IOException {
        try {
            Files.createFile(p, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(p);
        } catch (FileAlreadyExistsException e) {
            throw new IOException("temp file appeared concurrently: " + p, e);
        }
    }

    /** The stored state, or empty when there is none or it fails verification (logged). */
    public Optional<Lkg> load() {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            LOG.warn("last-known-good unreadable, ignoring file={} cause={}", file, e.toString());
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(bytes);
            JsonNode payload = root.path("payload");
            String expected = root.path("sha256").asText("");
            if (!payload.isObject() || !expected.equals(sha256(MAPPER.writeValueAsBytes(payload)))) {
                LOG.warn("last-known-good failed its checksum, ignoring file={}", file);
                return Optional.empty();
            }
            if (payload.path("format").asInt() != FORMAT || !prefix.equals(payload.path("prefix").asText())) {
                LOG.warn("last-known-good is for another format or prefix, ignoring file={}", file);
                return Optional.empty();
            }
            Base64.Decoder b64 = Base64.getDecoder();
            List<KeyValue> kvs = new ArrayList<>();
            for (JsonNode kv : payload.path("kvs")) {
                kvs.add(new KeyValue(kv.path("key").asText(), b64.decode(kv.path("value").asText()),
                        kv.path("createRevision").asLong(), kv.path("modRevision").asLong(),
                        kv.path("version").asLong(), 0));
            }
            return Optional.of(new Lkg(payload.path("revision").asLong(), kvs,
                    Instant.parse(payload.path("savedAt").asText())));
        } catch (IOException | IllegalArgumentException | java.time.format.DateTimeParseException e) {
            LOG.warn("last-known-good is malformed, ignoring file={} cause={}", file, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }
}
