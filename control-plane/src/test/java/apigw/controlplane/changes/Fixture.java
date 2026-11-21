package apigw.controlplane.changes;

import apigw.controlplane.Json;
import apigw.controlplane.audit.AuditLog;
import apigw.controlplane.auth.Actor;
import apigw.controlplane.auth.Role;
import apigw.controlplane.store.Kind;
import apigw.controlplane.store.MemoryKvStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Control-plane services over {@link MemoryKvStore}, for unit tests. */
final class Fixture implements AutoCloseable {

    static final String PREFIX = "/gw/test/";
    static final Actor EDITOR = new Actor("alice", Role.PUBLISHER, Set.of(Role.PUBLISHER), "10.0.0.1", "req-1");

    final Vertx vertx = Vertx.vertx();
    final MemoryKvStore kv = new MemoryKvStore();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC);
    final AuditLog audit = new AuditLog(PREFIX, kv, clock, Optional.empty(), 1000);
    final ChangeSets changeSets;
    final Publisher publisher;

    Fixture() {
        this(Limits.DEFAULTS);
    }

    Fixture(Limits limits) {
        changeSets = new ChangeSets(PREFIX, kv, audit, clock, limits);
        publisher = new Publisher(PREFIX, kv, changeSets, audit, new DryRun(vertx, clock), clock, limits);
        kv.put(PREFIX + "meta/schema-version", "1");
    }

    static <T> T await(Future<T> f) throws Exception {
        try {
            return f.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        } catch (TimeoutException e) {
            throw new AssertionError("future did not complete", e);
        }
    }

    String newChangeSet() throws Exception {
        return await(changeSets.create("test", EDITOR)).changeSet().id();
    }

    /** Stages a put of {@code spec} for {@code kind}/{@code id} based on its current revision. */
    void stagePut(String cs, Kind kind, String id, String specJson) throws Exception {
        String rel = kind == Kind.SETTINGS ? kind.keyPrefix() : kind.key(id);
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.set("spec", Json.parse(specJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ObjectNode env = Envelopes.build(kind, id, body, EDITOR.subject(), clock.instant());
        var errors = Envelopes.check(rel, env);
        if (!errors.isEmpty()) {
            throw new AssertionError("invalid test object: " + errors);
        }
        long live = await(kv.get(PREFIX + rel)).map(k -> k.modRevision()).orElse(0L);
        await(changeSets.stage(cs, ChangeSets.put(rel, env, live, EDITOR), EDITOR));
    }

    void stageDelete(String cs, String rel) throws Exception {
        long live = await(kv.get(PREFIX + rel)).map(k -> k.modRevision()).orElseThrow();
        await(changeSets.stage(cs, ChangeSets.delete(rel, live, EDITOR), EDITOR));
    }

    /** Settings + upstream u + service s + route a (/a), published as seq 1. */
    Publisher.Published seed() throws Exception {
        String cs = newChangeSet();
        stagePut(cs, Kind.SETTINGS, "settings", "{\"upstreamAllowCidrs\":[\"127.0.0.0/8\"]}");
        stagePut(cs, Kind.UPSTREAMS, "u", "{\"targets\":[{\"host\":\"127.0.0.1\",\"port\":9}]}");
        stagePut(cs, Kind.SERVICES, "s", "{\"upstreamId\":\"u\"}");
        stagePut(cs, Kind.ROUTES, "a", "{\"path\":\"/a\",\"serviceId\":\"s\"}");
        return await(publisher.publish(cs, null, EDITOR));
    }

    @Override
    public void close() {
        vertx.close();
    }
}
