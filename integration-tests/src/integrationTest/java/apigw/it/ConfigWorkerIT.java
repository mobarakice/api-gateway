package apigw.it;

import static apigw.it.EtcdGatewaySupport.await;
import static apigw.it.EtcdGatewaySupport.configState;
import static apigw.it.EtcdGatewaySupport.get;
import static apigw.it.EtcdGatewaySupport.status;
import static org.assertj.core.api.Assertions.assertThat;

import apigw.it.EtcdGatewaySupport.Ctl;
import apigw.it.EtcdGatewaySupport.Gw;
import apigw.testsupport.EtcdTestNode;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M3 config worker against a real etcd node (TLS + auth, gateway read-only role), with the gateway
 * running as a real process (JVM or native). Each test gets its own etcd node because several of
 * them kill it.
 */
class ConfigWorkerIT {

    private static final String PREFIX = "/gw/it/";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static HttpServer upstream;

    private EtcdTestNode etcd;
    private Ctl ctl;

    @TempDir
    Path tmp;

    @BeforeAll
    static void upstream() throws Exception {
        upstream = EtcdGatewaySupport.echo();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    @BeforeEach
    void etcd() {
        etcd = new EtcdTestNode(EtcdGatewaySupport.TLS, PREFIX).start();
        ctl = etcd::rootCtl;
        EtcdGatewaySupport.seed(ctl, PREFIX, upstream.getAddress().getPort());
    }

    @AfterEach
    void stopEtcd() {
        etcd.close();
    }

    private Gw env(String id) throws Exception {
        Path lkg = tmp.resolve("lkg");
        Files.createDirectories(lkg);
        return EtcdGatewaySupport.gatewayEnv(etcd.endpoint(), PREFIX, etcd.gatewayPassword(), lkg, id);
    }

    private long modRevision(String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"mod_revision\":(\\d+)")
                .matcher(etcd.rootCtl("get", key, "-w", "json"));
        assertThat(m.find()).as("mod_revision of " + key).isTrue();
        return Long.parseLong(m.group(1));
    }

    @Test
    void syncsWatchesAndHeartbeats() throws Exception {
        Gw g = env("gw-sync");
        try (GatewayProcess gw = GatewayProcess.start(List.of(), g.env())) {
            gw.awaitLine(l -> l.contains("\"msg\":\"ready revision="), WAIT);
            assertThat(get(g.http(), "/a").status()).isEqualTo(200);
            assertThat(get(g.status(), "/readyz").status()).isEqualTo(200);
            await(g.status(), "/configz", configState("SERVING"), WAIT, gw);

            // Propagation: add a route, then remove one.
            EtcdGatewaySupport.putRoute(ctl, PREFIX, "b", "/b");
            long addMs = await(g.http(), "/b", status(200), WAIT, gw);
            ctl.del(PREFIX + "routes/a");
            long delMs = await(g.http(), "/a", status(404), WAIT, gw);
            System.out.printf("[dev host] etcd put->served route add=%dms delete=%dms (mode=%s)%n", addMs, delMs,
                    GatewayProcess.mode());

            // Heartbeat: the gateway's only write, leased, reflects the applied revision. (Heartbeat writes
            // bump the store revision too, so take the config change's own mod_revision.)
            EtcdGatewaySupport.putRoute(ctl, PREFIX, "z", "/z");
            long rev = modRevision(PREFIX + "routes/z");
            await(g.http(), "/z", status(200), WAIT, gw);
            await(g.status(), "/configz", r -> r.body().contains("\"appliedRevision\":" + rev), WAIT, gw);
            String node = ctl.get(PREFIX + "nodes/gw-sync");
            assertThat(node).contains("\"state\":\"SERVING\"").contains("\"appliedRevision\":" + rev);
            assertThat(etcd.rootCtl("get", PREFIX + "nodes/gw-sync", "-w", "json")).contains("\"lease\":");

            // 143 = 128 + SIGTERM after the shutdown hook completed (same as the other process suites).
            assertThat(gw.terminate(Duration.ofSeconds(20))).isEqualTo(143);
            assertThat(gw.output()).anyMatch(l -> l.contains("shutdown complete"));
            // Graceful stop revokes the lease, which removes the key at once.
            assertThat(ctl.get(PREFIX + "nodes/gw-sync")).isBlank();
            String out = String.join("\n", gw.output());
            assertThat(out).doesNotContain(etcd.gatewayPassword());
        }
    }

    @Test
    void invalidChangeIsRejectedAndThePreviousSnapshotKeepsServing() throws Exception {
        Gw g = env("gw-invalid");
        try (GatewayProcess gw = GatewayProcess.start(List.of(), g.env())) {
            gw.awaitLine(l -> l.contains("\"msg\":\"ready revision="), WAIT);
            long good = modRevision(PREFIX + "routes/a");

            // References a service that does not exist: the whole new state is rejected.
            ctl.put(PREFIX + "routes/bad", EtcdGatewaySupport.envelope("Route", "bad",
                    "{\"path\":\"/bad\",\"serviceId\":\"missing\"}"));
            await(g.status(), "/configz", configState("INVALID_CONFIG"), WAIT, gw);
            String configz = get(g.status(), "/configz").body();
            assertThat(configz).contains("routes/bad").contains("\"appliedRevision\":" + good);
            assertThat(get(g.http(), "/a").status()).isEqualTo(200);
            assertThat(get(g.http(), "/bad").status()).isEqualTo(404);
            assertThat(get(g.status(), "/readyz").status()).isEqualTo(200);
            // A valid change in the same batch is not applied either: never part of a state.
            EtcdGatewaySupport.putRoute(ctl, PREFIX, "c", "/c");
            Thread.sleep(500);
            assertThat(get(g.http(), "/c").status()).isEqualTo(404);
            await(g.status(), "/configz", r -> ctl.get(PREFIX + "nodes/gw-invalid").contains("INVALID_CONFIG"),
                    WAIT, gw);

            // Malformed JSON is rejected the same way.
            ctl.put(PREFIX + "services/broken", "{not json");
            Thread.sleep(300);
            assertThat(get(g.http(), "/a").status()).isEqualTo(200);

            // Fixing it applies everything that is now valid.
            ctl.del(PREFIX + "routes/bad");
            ctl.del(PREFIX + "services/broken");
            await(g.http(), "/c", status(200), WAIT, gw);
            await(g.status(), "/configz", configState("SERVING"), WAIT, gw);
            assertThat(gw.output()).anyMatch(l -> l.contains("config change rejected") && l.contains("routes/bad"));
        }
    }

    @Test
    void etcdDownAtBootWithoutLkgStaysNotReadyThenRecovers() throws Exception {
        Gw g = env("gw-boot");
        etcd.kill();
        try (GatewayProcess gw = GatewayProcess.start(List.of(), g.env())) {
            gw.awaitLine(l -> l.contains("etcd unreachable, retrying"), WAIT);
            await(g.status(), "/configz", r -> r.body().contains("\"state\":\"NO_CONFIG\"")
                    && r.body().contains("etcdError"), WAIT, gw);
            assertThat(get(g.status(), "/readyz").status()).isEqualTo(503);
            assertThat(get(g.http(), "/a").status()).isEqualTo(503);

            etcd.restart();
            await(g.status(), "/readyz", status(200), WAIT, gw);
            assertThat(get(g.http(), "/a").status()).isEqualTo(200);
            await(g.status(), "/configz", configState("SERVING"), WAIT, gw);
        }
    }

    @Test
    void etcdRestartKeepsServingAndResumesTheWatch() throws Exception {
        Gw g = env("gw-restart");
        try (GatewayProcess gw = GatewayProcess.start(List.of(), g.env())) {
            gw.awaitLine(l -> l.contains("\"msg\":\"ready revision="), WAIT);
            etcd.kill();
            await(g.status(), "/configz", configState("SERVING_STALE"), WAIT, gw);
            for (int i = 0; i < 20; i++) {
                assertThat(get(g.http(), "/a").status()).isEqualTo(200);
            }
            assertThat(get(g.status(), "/readyz").status()).isEqualTo(200);

            etcd.restart();
            await(g.status(), "/configz", configState("SERVING"), WAIT, gw);
            EtcdGatewaySupport.putRoute(ctl, PREFIX, "after", "/after");
            await(g.http(), "/after", status(200), WAIT, gw);
        }
    }

    /**
     * Backup and total-loss restore (docs/operations/etcd-backup-restore.md) under a running gateway: a change made
     * after the snapshot is rolled back by the restore, the gateway converges to the restored state without a
     * restart (the revision bump + compaction forces its resync), keeps serving throughout, and later changes apply.
     */
    @Test
    void restoreFromSnapshotIsPickedUpByRunningGateways() throws Exception {
        Gw base = env("gw-restore");
        try (GatewayProcess gw = GatewayProcess.start(List.of(), base.env())) {
            gw.awaitLine(l -> l.contains("\"msg\":\"ready revision="), WAIT);
            Path snapshot = tmp.resolve("backup.db");
            etcd.snapshot(snapshot);
            EtcdGatewaySupport.putRoute(ctl, PREFIX, "after-backup", "/after-backup");
            await(base.http(), "/after-backup", status(200), WAIT, gw);

            etcd.restoreFrom(snapshot, 1_000_000_000L, tmp.resolve("restore-work"));
            await(base.http(), "/after-backup", status(404), WAIT, gw);
            assertThat(get(base.http(), "/a").status()).isEqualTo(200);
            await(base.status(), "/configz", configState("SERVING"), WAIT, gw);
            EtcdGatewaySupport.putRoute(ctl, PREFIX, "after-restore", "/after-restore");
            await(base.http(), "/after-restore", status(200), WAIT, gw);
            assertThat(get(base.status(), "/metrics").body()).contains("gw_config_resyncs_total 1");
        }
    }

    @Test
    void networkPartitionIsDetectedAndHealed() throws Exception {
        Gw g = env("gw-partition");
        try (GatewayProcess gw = GatewayProcess.start(List.of(), g.env())) {
            gw.awaitLine(l -> l.contains("\"msg\":\"ready revision="), WAIT);
            // Paused: connections stay open but nothing answers. The heartbeat's timeout must notice.
            etcd.pause();
            try {
                await(g.status(), "/configz", configState("SERVING_STALE"), WAIT, gw);
                assertThat(get(g.http(), "/a").status()).isEqualTo(200);
            } finally {
                etcd.unpause();
            }
            EtcdGatewaySupport.putRoute(ctl, PREFIX, "healed", "/healed");
            await(g.http(), "/healed", status(200), WAIT, gw);
        }
    }

    @Test
    void restoresLastKnownGoodWhenEtcdIsDownAtBoot() throws Exception {
        Gw g = env("gw-lkg");
        EtcdGatewaySupport.putRoute(ctl, PREFIX, "kept", "/kept");
        try (GatewayProcess first = GatewayProcess.start(List.of(), g.env())) {
            first.awaitLine(l -> l.contains("\"msg\":\"ready revision="), WAIT);
            await(g.http(), "/kept", status(200), WAIT, first);
            assertThat(first.terminate(Duration.ofSeconds(20))).isEqualTo(143);
        }
        Path lkgFile;
        try (var files = Files.list(Path.of(g.env().get("GW_LKG_DIR")))) {
            lkgFile = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
        }
        assertThat(Files.readString(lkgFile)).doesNotContain(etcd.gatewayPassword());

        etcd.kill();
        try (GatewayProcess second = GatewayProcess.start(List.of(), g.env())) {
            second.awaitLine(l -> l.contains("restored last-known-good snapshot"), WAIT);
            String ready = second.awaitLine(l -> l.contains("\"msg\":\"ready revision="), WAIT);
            // readyMs: from main() entry, as logged by the gateway; spawnMs: from process spawn, as seen by the
            // test (includes runtime start-up; up to ~5 ms polling slack).
            System.out.printf("[dev host] ready from LKG, etcd down (mode=%s): spawnMs=%d in-process %s%n",
                    GatewayProcess.mode(), second.millisSinceStart(), ready.replaceAll(".*(readyMs=\\d+).*", "$1"));
            await(g.status(), "/readyz", status(200), WAIT, second);
            assertThat(get(g.http(), "/kept").status()).isEqualTo(200);
            String configz = get(g.status(), "/configz").body();
            assertThat(configz).contains("\"state\":\"SERVING_STALE\"").contains("\"snapshotSource\":\"lkg\"");

            etcd.restart();
            await(g.status(), "/configz", r -> r.body().contains("\"state\":\"SERVING\"")
                    && r.body().contains("\"snapshotSource\":\"etcd\""), WAIT, second);
        }

        // A corrupted LKG is never served.
        Files.writeString(lkgFile, Files.readString(lkgFile).replace("/kept", "/kepx"));
        etcd.kill();
        try (GatewayProcess third = GatewayProcess.start(List.of(), g.env())) {
            third.awaitLine(l -> l.contains("last-known-good failed its checksum"), WAIT);
            assertThat(get(g.status(), "/readyz").status()).isEqualTo(503);
        }
    }
}
