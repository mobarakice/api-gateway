package apigw.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A single etcd node from the official image with client TLS and authentication enabled, plus the
 * gateway's read-only role. Credentials are random per instance and exist only for the test run.
 *
 * <p>Roles created by {@link #start()}:
 * <ul>
 *   <li>{@code root}: full access, used only by the test to seed data and trigger events.</li>
 *   <li>{@code gateway}: {@code read} on {@code <prefix>}, {@code readwrite} on {@code <prefix>nodes/} only.</li>
 * </ul>
 */
public final class EtcdTestNode implements AutoCloseable {

    public static final String ROOT_USER = "root";
    public static final String GATEWAY_USER = "gateway";
    private static final int CLIENT_PORT = 2379;
    private static final String CERTS = "/etc/etcd-tls/";

    private final GenericContainer<?> container;
    private final String prefix;
    private final String rootPassword = Secrets.randomPassword();
    private final String gatewayPassword = Secrets.randomPassword();

    @SuppressWarnings("resource")
    public EtcdTestNode(Path tlsDir, String prefix) {
        this.prefix = prefix;
        this.container = new GenericContainer<>(DockerImageName.parse(image()))
                .withExposedPorts(CLIENT_PORT)
                .withCopyFileToContainer(MountableFile.forHostPath(tlsDir.resolve("ca.pem")), CERTS + "ca.pem")
                .withCopyFileToContainer(MountableFile.forHostPath(tlsDir.resolve("server.pem")),
                        CERTS + "server.pem")
                .withCopyFileToContainer(MountableFile.forHostPath(tlsDir.resolve("server-key.pem"), 0644),
                        CERTS + "server-key.pem")
                .withCommand("etcd",
                        "--name", "etcd0",
                        "--data-dir", "/tmp/etcd-data",
                        "--listen-client-urls", "https://0.0.0.0:" + CLIENT_PORT,
                        "--advertise-client-urls", "https://localhost:" + CLIENT_PORT,
                        "--cert-file", CERTS + "server.pem",
                        "--key-file", CERTS + "server-key.pem",
                        // No --trusted-ca-file: in etcd 3.7 it makes client certificates mandatory.
                        // Clients authenticate with username/password over server-authenticated TLS.
                        "--auth-token", "simple")
                .waitingFor(Wait.forLogMessage(".*ready to serve client requests.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(60)));
    }

    /** Image reference pinned in {@code gradle/libs.versions.toml} ({@code etcdImage}). */
    public static String image() {
        Properties p = new Properties();
        try (InputStream in = EtcdTestNode.class.getResourceAsStream("/test-support.properties")) {
            if (in == null) {
                throw new IllegalStateException("test-support.properties missing from classpath");
            }
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p.getProperty("etcd.image");
    }

    /** Starts the node, enables auth and creates the root and gateway users and roles. */
    public EtcdTestNode start() {
        container.start();
        ctl("user", "add", ROOT_USER, "--new-user-password=" + rootPassword);
        ctl("user", "grant-role", ROOT_USER, "root");
        ctl("auth", "enable");
        rootCtl("role", "add", "gateway-read");
        rootCtl("role", "grant-permission", "gateway-read", "--prefix=true", "read", prefix);
        rootCtl("role", "grant-permission", "gateway-read", "--prefix=true", "readwrite", prefix + "nodes/");
        rootCtl("user", "add", GATEWAY_USER, "--new-user-password=" + gatewayPassword);
        rootCtl("user", "grant-role", GATEWAY_USER, "gateway-read");
        return this;
    }

    /** {@code https://host:port} reachable from the test JVM and from processes on the host. */
    public String endpoint() {
        return "https://localhost:" + container.getMappedPort(CLIENT_PORT);
    }

    public String prefix() {
        return prefix;
    }

    public String gatewayPassword() {
        return gatewayPassword;
    }

    /** Root password: tests use it only to prove a gateway configured with a writer role is rejected. */
    public String rootPassword() {
        return rootPassword;
    }

    /** Runs etcdctl inside the container as root and returns stdout. Fails on a non-zero exit. */
    public String rootCtl(String... args) {
        List<String> full = new ArrayList<>(List.of("--user", ROOT_USER + ":" + rootPassword));
        full.addAll(List.of(args));
        return ctl(full.toArray(String[]::new));
    }

    private String ctl(String... args) {
        List<String> cmd = new ArrayList<>(List.of("etcdctl",
                "--endpoints", "https://127.0.0.1:" + CLIENT_PORT,
                "--cacert", CERTS + "ca.pem",
                "--dial-timeout=5s", "--command-timeout=15s"));
        cmd.addAll(List.of(args));
        try {
            Container.ExecResult r = container.execInContainer(cmd.toArray(String[]::new));
            if (r.getExitCode() != 0) {
                // Never echo the arguments: they can contain passwords.
                throw new IllegalStateException("etcdctl exited " + r.getExitCode() + ": " + r.getStderr());
            }
            return r.getStdout();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running etcdctl", e);
        }
    }

    @Override
    public void close() {
        container.stop();
    }
}
