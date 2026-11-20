package apigw.config.worker;

import static org.assertj.core.api.Assertions.assertThat;

import apigw.config.etcd.KeyValue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LkgStoreTest {

    @TempDir
    Path dir;

    private static KeyValue kv(String k, String v, long rev) {
        return new KeyValue(k, v.getBytes(StandardCharsets.UTF_8), rev, rev, 1, 0);
    }

    @Test
    void roundTripsAtomicallyWithOwnerOnlyPermissions() throws Exception {
        LkgStore store = new LkgStore(dir.resolve("sub"), "/gw/prod/");
        assertThat(store.load()).isEmpty();
        store.save(7, List.of(kv("/gw/prod/routes/b", "{\"x\":2}", 7), kv("/gw/prod/routes/a", "é", 3)));
        LkgStore.Lkg l = store.load().orElseThrow();
        assertThat(l.revision()).isEqualTo(7);
        assertThat(l.kvs()).extracting(KeyValue::key).containsExactly("/gw/prod/routes/a", "/gw/prod/routes/b");
        assertThat(l.kvs().getFirst().valueUtf8()).isEqualTo("é");
        assertThat(l.kvs().getFirst().modRevision()).isEqualTo(3);
        assertThat(Files.getPosixFilePermissions(store.file()))
                .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        try (var files = Files.list(store.file().getParent())) {
            assertThat(files).containsExactly(store.file());
        }
        store.save(8, List.of());
        assertThat(store.load().orElseThrow().revision()).isEqualTo(8);
    }

    @Test
    void rejectsCorruptionTruncationAndOtherPrefixes() throws Exception {
        LkgStore store = new LkgStore(dir, "/gw/prod/");
        store.save(7, List.of(kv("/gw/prod/routes/a", "v", 7)));
        String good = Files.readString(store.file());

        Files.writeString(store.file(), good.replace("\"revision\":7", "\"revision\":9"));
        assertThat(store.load()).isEmpty();
        Files.writeString(store.file(), good.substring(0, good.length() / 2));
        assertThat(store.load()).isEmpty();
        Files.writeString(store.file(), good);
        assertThat(store.load()).isPresent();

        // Same file name pattern differs per prefix; a copied file for another prefix is refused.
        LkgStore other = new LkgStore(dir, "/gw/stage/");
        Files.copy(store.file(), other.file());
        assertThat(other.load()).isEmpty();
    }
}
