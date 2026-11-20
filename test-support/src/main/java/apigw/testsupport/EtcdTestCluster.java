package apigw.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * An etcd cluster of one or more members from the official image: client TLS with authentication,
 * peers over plain HTTP on a private Docker network, and the gateway's read-only role. Each member's
 * client port is bound to a fixed loopback port chosen at construction, so a member can be killed,
 * stopped, restarted or paused (network partition) and come back on the same endpoint with its data.
 *
 * <p>Roles created by {@link #start()}:
 * <ul>
 *   <li>{@code root}: full access, used only by the test to seed data and trigger events.</li>
 *   <li>{@code gateway}: {@code read} on {@code <prefix>}, {@code readwrite} on {@code <prefix>nodes/} only.</li>
 * </ul>
 */
public final class EtcdTestCluster implements AutoCloseable {

    public static final String ROOT_USER = "root";
    public static final String GATEWAY_USER = "gateway";
    static final int CLIENT_PORT = 2379;
    private static final int PEER_PORT = 2380;
    private static final String CERTS = "/etc/etcd-tls/";
    private static final Pattern MEMBER_ID = Pattern.compile("\"member_id\":(\\d+)");
    private static final Pattern LEADER = Pattern.compile("\"leader\":(\\d+)");
    private static final Pattern REVISION = Pattern.compile("\"revision\":(\\d+)");

    private final Network network = Network.newNetwork();
    private final List<Member> members = new ArrayList<>();
    private final String prefix;
    private final String rootPassword = Secrets.randomPassword();
    private final String gatewayPassword = Secrets.randomPassword();

    public EtcdTestCluster(Path tlsDir, String prefix, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1");
        }
        this.prefix = prefix;
        String initialCluster = java.util.stream.IntStream.range(0, size)
                .mapToObj(i -> "etcd" + i + "=http://etcd" + i + ":" + PEER_PORT)
                .collect(Collectors.joining(","));
        for (int i = 0; i < size; i++) {
            members.add(new Member(i, tlsDir, initialCluster));
        }
    }

    /** Image reference pinned in {@code gradle/libs.versions.toml} ({@code etcdImage}). */
    public static String image() {
        return EtcdTestNode.image();
    }

    /** Starts every member (in parallel: they need quorum), enables auth, creates users and roles. */
    public EtcdTestCluster start() {
        Startables.deepStart(members.stream().map(m -> m.container)).join();
        Member m = members.getFirst();
        m.exec(false, "user", "add", ROOT_USER, "--new-user-password=" + rootPassword);
        m.exec(false, "user", "grant-role", ROOT_USER, "root");
        m.exec(false, "auth", "enable");
        rootCtl("role", "add", "gateway-read");
        rootCtl("role", "grant-permission", "gateway-read", "--prefix=true", "read", prefix);
        rootCtl("role", "grant-permission", "gateway-read", "--prefix=true", "readwrite", prefix + "nodes/");
        rootCtl("user", "add", GATEWAY_USER, "--new-user-password=" + gatewayPassword);
        rootCtl("user", "grant-role", GATEWAY_USER, "gateway-read");
        return this;
    }

    public int size() {
        return members.size();
    }

    public Member member(int i) {
        return members.get(i);
    }

    /** Comma-separated client endpoints of every member, for {@code GW_ETCD_ENDPOINTS}. */
    public String endpoints() {
        return members.stream().map(Member::endpoint).collect(Collectors.joining(","));
    }

    public String prefix() {
        return prefix;
    }

    public String gatewayPassword() {
        return gatewayPassword;
    }

    public String rootPassword() {
        return rootPassword;
    }

    /**
     * Runs etcdctl as root on the first member that is up and returns stdout. Retries for up to 20 s
     * while the cluster reports a transient condition (election in progress, leader changed); fails on
     * any other non-zero exit.
     */
    public String rootCtl(String... args) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            Member m = members.stream().filter(x -> x.state == State.RUNNING).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no etcd member is running"));
            try {
                return m.exec(true, args);
            } catch (IllegalStateException e) {
                if (!transientFailure(e) || System.nanoTime() > deadline) {
                    throw e;
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", ie);
            }
        }
    }

    private static boolean transientFailure(IllegalStateException e) {
        String m = String.valueOf(e.getMessage());
        return m.contains("leader changed") || m.contains("no leader") || m.contains("timed out")
                || m.contains("Unavailable") || m.contains("DeadlineExceeded");
    }

    /** Current store revision. */
    public long revision() {
        return firstLong(REVISION, rootCtl("endpoint", "status", "-w", "json"));
    }

    /** Compacts the store up to (excluding) {@code revision}, physically discarding older history. */
    public void compact(long revision) {
        rootCtl("compaction", "--physical", Long.toString(revision));
    }

    /** Index of the current leader, as reported by a running member. */
    public int leaderIndex() {
        String status = rootCtl("endpoint", "status", "-w", "json");
        long leader = firstLong(LEADER, status);
        for (Member m : members) {
            if (m.state == State.RUNNING && m.memberId() == leader) {
                return m.index;
            }
        }
        throw new IllegalStateException("leader is not a running member");
    }

    /** Transfers leadership to member {@code target} (must be sent to the current leader). */
    public void moveLeader(int target) {
        Member leader = members.get(leaderIndex());
        leader.exec(true, "move-leader", Long.toHexString(members.get(target).memberId()));
    }

    private static long firstLong(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) {
            throw new IllegalStateException("unexpected etcdctl output");
        }
        return Long.parseUnsignedLong(m.group(1));
    }

    @Override
    public void close() {
        for (Member m : members) {
            if (m.state == State.PAUSED) {
                m.unpause();
            }
            m.container.stop();
        }
        network.close();
    }

    enum State { RUNNING, STOPPED, PAUSED }

    /** One cluster member. */
    public final class Member {
        private final int index;
        private final int hostPort;
        private final GenericContainer<?> container;
        private volatile State state = State.RUNNING;

        @SuppressWarnings("resource")
        Member(int index, Path tlsDir, String initialCluster) {
            this.index = index;
            this.hostPort = freePort();
            String name = "etcd" + index;
            this.container = new GenericContainer<>(DockerImageName.parse(image()))
                    .withNetwork(network)
                    .withNetworkAliases(name)
                    .withExposedPorts(CLIENT_PORT)
                    .withCreateContainerCmdModifier(cmd -> bindClientPort(cmd, hostPort))
                    .withCopyFileToContainer(MountableFile.forHostPath(tlsDir.resolve("ca.pem")), CERTS + "ca.pem")
                    .withCopyFileToContainer(MountableFile.forHostPath(tlsDir.resolve("server.pem")),
                            CERTS + "server.pem")
                    .withCopyFileToContainer(MountableFile.forHostPath(tlsDir.resolve("server-key.pem"), 0644),
                            CERTS + "server-key.pem")
                    .withCommand("etcd",
                            "--name", name,
                            "--data-dir", "/tmp/etcd-data",
                            "--listen-client-urls", "https://0.0.0.0:" + CLIENT_PORT,
                            "--advertise-client-urls", "https://localhost:" + hostPort,
                            "--listen-peer-urls", "http://0.0.0.0:" + PEER_PORT,
                            "--initial-advertise-peer-urls", "http://" + name + ":" + PEER_PORT,
                            "--initial-cluster", initialCluster,
                            "--initial-cluster-state", "new",
                            "--cert-file", CERTS + "server.pem",
                            "--key-file", CERTS + "server-key.pem",
                            // No --trusted-ca-file: in etcd 3.7 it makes client certificates mandatory.
                            // Clients authenticate with username/password over server-authenticated TLS.
                            "--auth-token", "simple")
                    .waitingFor(Wait.forLogMessage(".*ready to serve client requests.*", 1)
                            .withStartupTimeout(Duration.ofSeconds(90)));
        }

        public int index() {
            return index;
        }

        /** {@code https://localhost:port}, stable across restarts. */
        public String endpoint() {
            return "https://localhost:" + hostPort;
        }

        long memberId() {
            return firstLong(MEMBER_ID, exec(true, "endpoint", "status", "-w", "json"));
        }

        /** SIGKILL: a crash. The container's data directory survives for {@link #restart()}. */
        public void kill() {
            container.getDockerClient().killContainerCmd(container.getContainerId()).exec();
            state = State.STOPPED;
        }

        /** Starts a killed member again and waits until its client port accepts TLS connections. */
        public void restart() {
            container.getDockerClient().startContainerCmd(container.getContainerId()).exec();
            state = State.RUNNING;
            awaitHealthy(Duration.ofSeconds(60));
        }

        /** Freezes the member: connections stay open but nothing answers (a network partition). */
        public void pause() {
            container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
            state = State.PAUSED;
        }

        public void unpause() {
            container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
            state = State.RUNNING;
        }

        private void awaitHealthy(Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            RuntimeException last = null;
            while (System.nanoTime() < deadline) {
                try {
                    exec(true, "endpoint", "health");
                    return;
                } catch (IllegalStateException e) {
                    last = e;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
            throw new IllegalStateException("etcd member " + index + " did not become healthy", last);
        }

        String exec(boolean asRoot, String... args) {
            List<String> cmd = new ArrayList<>(List.of("etcdctl",
                    "--endpoints", "https://127.0.0.1:" + CLIENT_PORT,
                    "--cacert", CERTS + "ca.pem",
                    "--dial-timeout=5s", "--command-timeout=15s"));
            if (asRoot) {
                cmd.addAll(List.of("--user", ROOT_USER + ":" + rootPassword));
            }
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
    }

    private static void bindClientPort(com.github.dockerjava.api.command.CreateContainerCmd cmd, int hostPort) {
        var binding = new com.github.dockerjava.api.model.PortBinding(
                com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1", hostPort),
                new com.github.dockerjava.api.model.ExposedPort(CLIENT_PORT));
        java.util.Objects.requireNonNull(cmd.getHostConfig(), "host config").withPortBindings(binding);
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress("127.0.0.1", 0));
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
