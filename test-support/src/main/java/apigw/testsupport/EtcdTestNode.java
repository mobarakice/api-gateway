package apigw.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * A single etcd node from the official image with client TLS and authentication enabled, plus the
 * gateway's read-only role: a one-member {@link EtcdTestCluster}. Credentials are random per
 * instance and exist only for the test run. The endpoint is stable across {@link #kill()} /
 * {@link #restart()}, and data survives them.
 */
public final class EtcdTestNode implements AutoCloseable {

    public static final String ROOT_USER = EtcdTestCluster.ROOT_USER;
    public static final String GATEWAY_USER = EtcdTestCluster.GATEWAY_USER;

    private final EtcdTestCluster cluster;

    public EtcdTestNode(Path tlsDir, String prefix) {
        this.cluster = new EtcdTestCluster(tlsDir, prefix, 1);
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
        cluster.start();
        return this;
    }

    /** {@code https://localhost:port} reachable from the test JVM and from processes on the host. */
    public String endpoint() {
        return cluster.member(0).endpoint();
    }

    public String prefix() {
        return cluster.prefix();
    }

    public String gatewayPassword() {
        return cluster.gatewayPassword();
    }

    /** Root password: tests use it only to prove a gateway configured with a writer role is rejected. */
    public String rootPassword() {
        return cluster.rootPassword();
    }

    /** Runs etcdctl inside the container as root and returns stdout. Fails on a non-zero exit. */
    public String rootCtl(String... args) {
        return cluster.rootCtl(args);
    }

    public long revision() {
        return cluster.revision();
    }

    public void compact(long revision) {
        cluster.compact(revision);
    }

    /** Saves an etcd snapshot of the node to {@code target} on the host. */
    public void snapshot(Path target) {
        cluster.member(0).snapshot(target);
    }

    /** Discards the node and its data, then restores {@code snapshot} (revision bumped, marked compacted). */
    public void restoreFrom(Path snapshot, long bumpRevision, Path workDir) throws java.io.IOException {
        cluster.member(0).restoreFrom(snapshot, bumpRevision, workDir);
    }

    /** SIGKILL the node; data survives for {@link #restart()}. */
    public void kill() {
        cluster.member(0).kill();
    }

    public void restart() {
        cluster.member(0).restart();
    }

    public void pause() {
        cluster.member(0).pause();
    }

    public void unpause() {
        cluster.member(0).unpause();
    }

    @Override
    public void close() {
        cluster.close();
    }
}
