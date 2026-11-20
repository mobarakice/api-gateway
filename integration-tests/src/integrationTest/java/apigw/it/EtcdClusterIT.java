package apigw.it;

import static apigw.it.EtcdGatewaySupport.await;
import static apigw.it.EtcdGatewaySupport.configState;
import static apigw.it.EtcdGatewaySupport.get;
import static apigw.it.EtcdGatewaySupport.status;
import static org.assertj.core.api.Assertions.assertThat;

import apigw.it.EtcdGatewaySupport.Ctl;
import apigw.it.EtcdGatewaySupport.Gw;
import apigw.testsupport.EtcdTestCluster;
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
 * M3 config worker against a 3-member etcd cluster (TLS + auth): leader changes and compaction while
 * the gateway's watch is disconnected.
 */
class EtcdClusterIT {

    private static final String PREFIX = "/gw/cluster/";
    private static final Duration WAIT = Duration.ofSeconds(45);
    private static HttpServer upstream;

    private EtcdTestCluster etcd;
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
        etcd = new EtcdTestCluster(EtcdGatewaySupport.TLS, PREFIX, 3).start();
        ctl = etcd::rootCtl;
        EtcdGatewaySupport.seed(ctl, PREFIX, upstream.getAddress().getPort());
    }

    @AfterEach
    void stopEtcd() {
        etcd.close();
    }

    private Gw env(String endpoints, String id) throws Exception {
        Path lkg = tmp.resolve("lkg");
        Files.createDirectories(lkg);
        return EtcdGatewaySupport.gatewayEnv(endpoints, PREFIX, etcd.gatewayPassword(), lkg, id);
    }

    @Test
    void leaderChangeAndLeaderLoss() throws Exception {
        Gw g = env(etcd.endpoints(), "gw-leader");
        try (GatewayProcess gw = GatewayProcess.start(List.of(), g.env())) {
            gw.awaitLine(l -> l.contains("\"msg\":\"ready revision="), WAIT);

            // Planned leader transfer.
            int leader = etcd.leaderIndex();
            int target = (leader + 1) % etcd.size();
            etcd.moveLeader(target);
            assertThat(etcd.leaderIndex()).isEqualTo(target);
            EtcdGatewaySupport.putRoute(ctl, PREFIX, "moved", "/moved");
            await(g.http(), "/moved", status(200), WAIT, gw);

            // Leader crash: a new election, and the gateway's member may be the one that died.
            int crashed = etcd.leaderIndex();
            etcd.member(crashed).kill();
            EtcdGatewaySupport.putRoute(ctl, PREFIX, "elected", "/elected");
            await(g.http(), "/elected", status(200), WAIT, gw);
            assertThat(get(g.http(), "/a").status()).isEqualTo(200);

            etcd.member(crashed).restart();
            await(g.status(), "/configz", configState("SERVING"), WAIT, gw);
        }
    }

    @Test
    void partitionedMemberIsAbandonedForAHealthyOne() throws Exception {
        Gw g = env(etcd.endpoints(), "gw-partition");
        try (GatewayProcess gw = GatewayProcess.start(List.of(), g.env())) {
            gw.awaitLine(l -> l.contains("\"msg\":\"ready revision="), WAIT);
            // The client starts on the first endpoint. Freeze it: its TCP connections stay open but
            // silent, so only a timeout (heartbeat keepalive) can reveal the partition.
            etcd.member(0).pause();
            try {
                long t0 = System.nanoTime();
                EtcdGatewaySupport.putRoute(ctl, PREFIX, "failover", "/failover");
                await(g.http(), "/failover", status(200), WAIT, gw);
                System.out.printf("[dev host] partitioned member -> change served via another member: %dms (mode=%s)%n",
                        (System.nanoTime() - t0) / 1_000_000, GatewayProcess.mode());
                assertThat(get(g.http(), "/a").status()).isEqualTo(200);
            } finally {
                etcd.member(0).unpause();
            }
        }
    }

    @Test
    void compactionWhileDisconnectedForcesAFullResync() throws Exception {
        // Only member 0, so the gateway cannot fail over and must resume on the same member.
        Gw g = env(etcd.member(0).endpoint(), "gw-compact");
        try (GatewayProcess gw = GatewayProcess.start(List.of(), g.env())) {
            gw.awaitLine(l -> l.contains("\"msg\":\"ready revision="), WAIT);
            etcd.member(0).kill();
            await(g.status(), "/configz", configState("SERVING_STALE"), WAIT, gw);

            // Changes the gateway never saw as events, then their history is compacted away.
            EtcdGatewaySupport.putRoute(ctl, PREFIX, "missed", "/missed");
            ctl.del(PREFIX + "routes/a");
            etcd.compact(etcd.revision());

            etcd.member(0).restart();
            gw.awaitLine(l -> l.contains("compacted") && l.contains("resyncing"), WAIT);
            await(g.http(), "/missed", status(200), WAIT, gw);
            await(g.http(), "/a", status(404), WAIT, gw);
            await(g.status(), "/configz", configState("SERVING"), WAIT, gw);
        }
    }
}
