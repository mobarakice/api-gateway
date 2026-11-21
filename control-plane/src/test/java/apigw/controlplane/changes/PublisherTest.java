package apigw.controlplane.changes;

import static apigw.controlplane.changes.Fixture.EDITOR;
import static apigw.controlplane.changes.Fixture.PREFIX;
import static apigw.controlplane.changes.Fixture.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import apigw.controlplane.Json;
import apigw.controlplane.api.ApiException;
import apigw.controlplane.store.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PublisherTest {

    private final Fixture f = new Fixture();

    @AfterEach
    void close() {
        f.close();
    }

    private JsonNode stored(String rel) {
        return Json.parse(f.kv.value(PREFIX + rel).orElseThrow().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void publishWritesEverythingInOneTransactionWithHistoryAndAudit() throws Exception {
        int before = f.kv.txnCount();
        Publisher.Published p = f.seed();
        assertThat(p.seq()).isEqualTo(1);
        // create + 4 stages + 1 publish
        assertThat(f.kv.txnCount() - before).isEqualTo(6);
        assertThat(stored("routes/a").path("spec").path("path").asText()).isEqualTo("/a");
        assertThat(stored("routes/a").path("kind").asText()).isEqualTo("Route");
        assertThat(stored("routes/a").path("updatedBy").asText()).isEqualTo("alice");
        assertThat(stored("history/head").path("seq").asLong()).isEqualTo(1);
        JsonNode entry = stored("history/entries/000000000001");
        assertThat(entry.path("ops")).hasSize(4);
        assertThat(entry.path("ops").get(0).path("before").isNull()).isTrue();
        assertThat(f.kv.keys(PREFIX + "changesets/")).isEmpty();
        assertThat(f.kv.keys(PREFIX + "audit/")).anySatisfy(k -> assertThat(f.kv.value(k).orElseThrow())
                .contains("\"action\":\"publish\"").contains("\"seq\":1").contains("\"subject\":\"alice\""));
        // Every key the publish wrote has the same mod revision: one transaction.
        long rev = f.kv.revision();
        for (String k : new String[] {"routes/a", "services/s", "upstreams/u", "meta/settings", "history/head"}) {
            assertThat(await(f.kv.get(PREFIX + k)).orElseThrow().modRevision()).as(k).isEqualTo(rev);
        }
    }

    @Test
    void concurrentEditsOfTheSameObjectConflictWith412() throws Exception {
        f.seed();
        String one = f.newChangeSet();
        String two = f.newChangeSet();
        f.stagePut(one, Kind.ROUTES, "a", "{\"path\":\"/one\",\"serviceId\":\"s\"}");
        f.stagePut(two, Kind.ROUTES, "a", "{\"path\":\"/two\",\"serviceId\":\"s\"}");
        await(f.publisher.publish(one, null, EDITOR));
        assertThatThrownBy(() -> await(f.publisher.publish(two, null, EDITOR)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(412);
                    assertThat(e.conflicts()).singleElement().satisfies(c -> assertThat(c.key()).isEqualTo("routes/a"));
                });
        assertThat(stored("routes/a").path("spec").path("path").asText()).isEqualTo("/one");
        // The losing draft is kept for the editor to rebase.
        assertThat(f.kv.keys(PREFIX + "changesets/")).hasSize(1);
    }

    @Test
    void invalidResultIsRejectedWithKeyPathsAndNothingIsWritten() throws Exception {
        f.seed();
        String cs = f.newChangeSet();
        f.stagePut(cs, Kind.ROUTES, "b", "{\"path\":\"/b\",\"serviceId\":\"missing\"}");
        long rev = f.kv.revision();
        assertThatThrownBy(() -> await(f.publisher.publish(cs, null, EDITOR)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.errors()).anySatisfy(err -> assertThat(err.path()).startsWith("routes/b"));
                });
        assertThat(f.kv.revision()).isEqualTo(rev);
        Publisher.Validation v = await(f.publisher.validate(await(f.changeSets.get(cs)).changeSet()));
        assertThat(v.valid()).isFalse();
        assertThat(v.errors()).isNotEmpty();
    }

    @Test
    void aConcurrentPublishElsewhereIsRevalidatedNotBlindlyCommitted() throws Exception {
        f.seed();
        // Draft deletes service s's only route and the service itself; meanwhile another publish adds a
        // route that references s. Committing the delete would leave a dangling reference.
        String del = f.newChangeSet();
        f.stageDelete(del, "routes/a");
        f.stageDelete(del, "services/s");
        String add = f.newChangeSet();
        f.stagePut(add, Kind.ROUTES, "c", "{\"path\":\"/c\",\"serviceId\":\"s\"}");
        f.kv.beforeNextTxn(() -> f.publisher.publish(add, null, EDITOR));
        assertThatThrownBy(() -> await(f.publisher.publish(del, null, EDITOR)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.errors()).anySatisfy(err -> assertThat(err.path()).startsWith("routes/c"));
                });
        assertThat(f.kv.value(PREFIX + "services/s")).isPresent();
        assertThat(f.kv.value(PREFIX + "routes/c")).isPresent();
    }

    @Test
    void anUnrelatedConcurrentPublishIsRetriedTransparently() throws Exception {
        f.seed();
        String mine = f.newChangeSet();
        f.stagePut(mine, Kind.ROUTES, "x", "{\"path\":\"/x\",\"serviceId\":\"s\"}");
        String theirs = f.newChangeSet();
        f.stagePut(theirs, Kind.ROUTES, "y", "{\"path\":\"/y\",\"serviceId\":\"s\"}");
        f.kv.beforeNextTxn(() -> f.publisher.publish(theirs, null, EDITOR));
        Publisher.Published p = await(f.publisher.publish(mine, null, EDITOR));
        assertThat(p.seq()).isEqualTo(3);
        assertThat(f.kv.value(PREFIX + "routes/x")).isPresent();
        assertThat(f.kv.value(PREFIX + "routes/y")).isPresent();
    }

    @Test
    void rollbackRestoresThePriorStateAsANewPublish() throws Exception {
        f.seed();
        String cs = f.newChangeSet();
        f.stagePut(cs, Kind.ROUTES, "a", "{\"path\":\"/changed\",\"serviceId\":\"s\"}");
        f.stagePut(cs, Kind.ROUTES, "b", "{\"path\":\"/b\",\"serviceId\":\"s\"}");
        Publisher.Published change = await(f.publisher.publish(cs, null, EDITOR));
        Publisher.Published rb = await(f.publisher.rollback(change.seq(), EDITOR));
        assertThat(rb.seq()).isEqualTo(change.seq() + 1);
        assertThat(stored("routes/a").path("spec").path("path").asText()).isEqualTo("/a");
        assertThat(f.kv.value(PREFIX + "routes/b")).isEmpty();
        JsonNode entry = await(f.publisher.entry(rb.seq()));
        assertThat(entry.path("rollbackOf").asLong()).isEqualTo(change.seq());
        // Rolling back the same entry again would undo nothing it can see: refused.
        assertThatThrownBy(() -> await(f.publisher.rollback(change.seq(), EDITOR)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(409));
        // ...but the rollback itself can be rolled back.
        await(f.publisher.rollback(rb.seq(), EDITOR));
        assertThat(stored("routes/a").path("spec").path("path").asText()).isEqualTo("/changed");
    }

    @Test
    void rollbackIsRefusedWhenALaterPublishChangedTheSameKeys() throws Exception {
        f.seed();
        String one = f.newChangeSet();
        f.stagePut(one, Kind.ROUTES, "a", "{\"path\":\"/one\",\"serviceId\":\"s\"}");
        Publisher.Published p1 = await(f.publisher.publish(one, null, EDITOR));
        String two = f.newChangeSet();
        f.stagePut(two, Kind.ROUTES, "a", "{\"path\":\"/two\",\"serviceId\":\"s\"}");
        await(f.publisher.publish(two, null, EDITOR));
        assertThatThrownBy(() -> await(f.publisher.rollback(p1.seq(), EDITOR)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("routes/a");
                });
    }

    @Test
    void historyIsBoundedAndCredentialHashesNeverLeaveThroughHistoryOrDiff() throws Exception {
        try (Fixture small = new Fixture(new Limits(128, 1_000_000, 256 * 1024, 2))) {
            small.seed();
            for (int i = 0; i < 3; i++) {
                String cs = small.newChangeSet();
                small.stagePut(cs, Kind.ROUTES, "r" + i, "{\"path\":\"/r" + i + "\",\"serviceId\":\"s\"}");
                await(small.publisher.publish(cs, null, EDITOR));
            }
            assertThat(small.kv.keys(PREFIX + "history/entries/")).hasSize(2);
            assertThatThrownBy(() -> await(small.publisher.rollback(1, EDITOR)))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
        }
        f.seed();
        String cs = f.newChangeSet();
        f.stagePut(cs, Kind.CONSUMERS, "c1", "{}");
        String hash = apigw.model.ApiKeyHash.hash(apigw.model.ApiKeyHash.newSecret());
        String rel = Kind.credentialKey("c1", "k1");
        com.fasterxml.jackson.databind.node.ObjectNode body = Json.MAPPER.createObjectNode();
        body.putObject("spec").put("type", "api-key").put("keyId", "abcdefgh12").put("hash", hash);
        var env = Envelopes.build(Kind.CREDENTIALS, "k1", body, "alice", f.clock.instant());
        assertThat(Envelopes.check(rel, env)).isEmpty();
        await(f.changeSets.stage(cs, ChangeSets.put(rel, env, 0, EDITOR), EDITOR));
        var diff = await(f.publisher.diff(await(f.changeSets.get(cs)).changeSet()));
        assertThat(diff.toString()).doesNotContain(hash).contains("abcdefgh12");
        Publisher.Published p = await(f.publisher.publish(cs, null, EDITOR));
        assertThat(f.kv.value(PREFIX + rel).orElseThrow()).contains(hash);
        assertThat(Json.string(await(f.publisher.entry(p.seq())))).doesNotContain(hash);
        assertThat(Json.string(Json.MAPPER.valueToTree(await(f.publisher.history())))).doesNotContain(hash);
    }

    @Test
    void oversizedTransactionsAreRefusedBeforeReachingEtcd() throws Exception {
        try (Fixture tiny = new Fixture(new Limits(128, 4096, 256 * 1024, 100))) {
            String cs = tiny.newChangeSet();
            tiny.stagePut(tiny.newChangeSet(), Kind.SETTINGS, "settings", "{}");
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 80; i++) {
                big.append(",\"x-h").append(i).append("\":\"").append("v".repeat(20)).append('"');
            }
            tiny.stagePut(cs, Kind.UPSTREAMS, "u", "{\"targets\":[{\"host\":\"127.0.0.1\",\"port\":9}]}");
            assertThatThrownBy(() -> tiny.stagePut(cs, Kind.ROUTES, "a", "{\"path\":\"/a\",\"serviceId\":\"s\","
                    + "\"headers\":{" + big.substring(1) + "}}"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(422));
        }
    }
}
