package apigw.controlplane.audit;

import static org.assertj.core.api.Assertions.assertThat;

import apigw.controlplane.auth.Actor;
import apigw.controlplane.auth.Role;
import apigw.controlplane.store.MemoryKvStore;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AuditLogTest {

    private static final Actor BOB = new Actor("bob", Role.VIEWER, Set.of(Role.VIEWER), "192.0.2.7", "rid");

    @Test
    void refusedAttemptsReachTheSinkButNotEtcd() {
        MemoryKvStore kv = new MemoryKvStore();
        List<String> sunk = new CopyOnWriteArrayList<>();
        AuditLog log = new AuditLog("/p/", kv, Clock.systemUTC(), Optional.of(sunk::add), 10);
        log.refused(AuditLog.Event.refused("publish", "denied", BOB, "cs-1", "needs publisher"));
        assertThat(sunk).singleElement().satisfies(l -> assertThat(l).contains("\"outcome\":\"denied\"")
                .contains("\"subject\":\"bob\"").contains("\"sourceIp\":\"192.0.2.7\"").contains("\"detail\""));
        assertThat(kv.keys("/p/audit/")).isEmpty();
    }

    @Test
    void theEtcdWindowIsTrimmedOldestFirst() throws Exception {
        MemoryKvStore kv = new MemoryKvStore();
        AuditLog log = new AuditLog("/p/", kv, Clock.systemUTC(), Optional.empty(), 10);
        for (int i = 0; i < 15; i++) {
            AuditLog.Prepared p = log.prepare(AuditLog.Event.success("changeset.stage", BOB, "routes/r" + i, "cs", null,
                    List.of()));
            kv.put(p.key(), apigw.controlplane.Json.string(p.json()));
            Thread.sleep(1);
        }
        List<String> before = kv.keys("/p/audit/");
        assertThat(log.trim().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)).isEqualTo(5);
        assertThat(kv.keys("/p/audit/")).containsExactlyElementsOf(before.subList(5, 15));
        var recent = log.recent(3, null).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(recent).extracting(n -> n.path("target").asText()).containsExactly("routes/r14", "routes/r13",
                "routes/r12");
    }
}
