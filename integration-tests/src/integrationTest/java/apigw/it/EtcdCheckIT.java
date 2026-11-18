package apigw.it;

import static org.assertj.core.api.Assertions.assertThat;

import apigw.testsupport.EtcdTestNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M1 etcd spike, end to end: {@code gateway check-etcd} (JVM or native binary) connects to etcd over
 * TLS with the gateway's read-only role, reads, confirms it cannot write config, and watches a key.
 */
class EtcdCheckIT {

    private static final String PREFIX = "/gw/check/";
    private static final Path TLS = Path.of(System.getProperty("gw.test.tls.dir"));
    private static EtcdTestNode etcd;

    @TempDir
    Path tmp;

    @BeforeAll
    static void start() {
        etcd = new EtcdTestNode(TLS, PREFIX).start();
        etcd.rootCtl("put", PREFIX + "meta/schema-version", "1");
    }

    @AfterAll
    static void stop() {
        etcd.close();
    }

    private Path passwordFile(String password) throws IOException {
        Path f = tmp.resolve("etcd-password");
        Files.writeString(f, password + "\n");
        return f;
    }

    private List<String> args(String user, Path passwordFile, String... extra) {
        List<String> base = new java.util.ArrayList<>(List.of("check-etcd",
                "--endpoints", etcd.endpoint(),
                "--cacert", TLS.resolve("ca.pem").toString(),
                "--user", user,
                "--password-file", passwordFile.toString(),
                "--prefix", PREFIX,
                "--timeout-seconds", "20"));
        base.addAll(List.of(extra));
        return base;
    }

    @Test
    void readsDeniesWritesAndReceivesAWatchEvent() throws Exception {
        String password = etcd.gatewayPassword();
        try (GatewayProcess gw = GatewayProcess.start(args(EtcdTestNode.GATEWAY_USER, passwordFile(password),
                "--expect-event"), Map.of())) {
            gw.awaitLine(l -> l.contains("\"stage\":\"watch-created\""), Duration.ofSeconds(30));
            etcd.rootCtl("put", PREFIX + "routes/r-watch", "{}");
            assertThat(gw.waitForExit(Duration.ofSeconds(30))).as(String.join("\n", gw.output())).isZero();
            assertThat(gw.output()).anyMatch(l -> l.contains("\"stage\":\"authenticate\",\"ok\":true"));
            assertThat(gw.output()).anyMatch(l -> l.contains("\"stage\":\"range\",\"ok\":true")
                    && l.contains("\"keys\":1"));
            assertThat(gw.output()).anyMatch(l -> l.contains("\"stage\":\"write-denied\",\"ok\":true"));
            assertThat(gw.output()).anyMatch(l -> l.contains("\"stage\":\"watch-event\"")
                    && l.contains(PREFIX + "routes/r-watch"));
            assertThat(gw.output()).anyMatch(l -> l.contains("\"stage\":\"summary\",\"ok\":true"));
            assertThat(String.join("\n", gw.output())).doesNotContain(password);
            assertThat(etcd.rootCtl("get", PREFIX + "routes/__gateway_write_probe")).isBlank();
        }
    }

    @Test
    void failsWhenTheRoleCanWriteConfig() throws Exception {
        // root can write anywhere: a gateway configured like this violates invariant 4.
        String rootPassword = rootPassword();
        try (GatewayProcess gw = GatewayProcess.start(args(EtcdTestNode.ROOT_USER, passwordFile(rootPassword)),
                Map.of())) {
            assertThat(gw.waitForExit(Duration.ofSeconds(30))).isEqualTo(1);
            assertThat(gw.output()).anyMatch(l -> l.contains("\"stage\":\"write-denied\",\"ok\":false"));
            assertThat(String.join("\n", gw.output())).doesNotContain(rootPassword);
        }
    }

    @Test
    void failsWithWrongPasswordWithoutLeakingIt() throws Exception {
        try (GatewayProcess gw = GatewayProcess.start(args(EtcdTestNode.GATEWAY_USER, passwordFile("wrong-pass-123")),
                Map.of())) {
            assertThat(gw.waitForExit(Duration.ofSeconds(30))).isEqualTo(1);
            assertThat(gw.output()).anyMatch(l -> l.contains("\"stage\":\"summary\",\"ok\":false")
                    && l.contains("authentication failed"));
            assertThat(String.join("\n", gw.output())).doesNotContain("wrong-pass-123");
        }
    }

    private static String rootPassword() {
        return etcd.rootPassword();
    }
}
