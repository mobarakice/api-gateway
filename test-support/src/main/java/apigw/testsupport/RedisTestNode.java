package apigw.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A Redis node (official image, pinned in {@code gradle/libs.versions.toml}) with ACLs: the default
 * user is disabled and the gateway user may only run the commands it needs on {@code gw*} keys. Can
 * be paused (connections hang: timeouts) and resumed, for Redis-down tests.
 */
public final class RedisTestNode implements AutoCloseable {

    public static final String USER = "gateway";
    private static final String COMMANDS = "+ping +auth +select +get +set +eval +evalsha +hmget +hset +pexpire "
            + "+incrby +time";

    private final String password = Secrets.randomPassword();
    private final GenericContainer<?> container;

    @SuppressWarnings("resource")
    public RedisTestNode() {
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of("redis-server", "--save", "",
                "--appendonly", "no", "--user", "default", "off", "--user", USER, "on", ">" + password, "~gw*"));
        cmd.addAll(java.util.List.of(COMMANDS.split(" ")));
        container = new GenericContainer<>(DockerImageName.parse(image()))
                .withExposedPorts(6379)
                .withCommand(cmd.toArray(String[]::new))
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
    }

    /** Image reference pinned in {@code gradle/libs.versions.toml} ({@code redisImage}). */
    public static String image() {
        Properties p = new Properties();
        try (InputStream in = RedisTestNode.class.getResourceAsStream("/test-support.properties")) {
            if (in == null) {
                throw new IllegalStateException("test-support.properties missing from classpath");
            }
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p.getProperty("redis.image");
    }

    public RedisTestNode start() {
        container.start();
        return this;
    }

    public String url() {
        return "redis://" + container.getHost() + ":" + container.getMappedPort(6379);
    }

    public String password() {
        return password;
    }

    /** Freezes the server: open connections stop answering (timeouts), new ones are not accepted. */
    public void pause() {
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
    }

    public void resume() {
        container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
    }

    @Override
    public void close() {
        container.close();
    }
}
