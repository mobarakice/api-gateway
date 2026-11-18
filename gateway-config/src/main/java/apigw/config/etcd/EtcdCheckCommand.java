package apigw.config.etcd;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code gateway check-etcd}: verifies what a gateway needs from etcd, using the gateway's own
 * credentials, then exits. It is the operator's connectivity check and the M1 native spike.
 *
 * <ol>
 *   <li>Authenticate (TLS, username/password).</li>
 *   <li>Count keys under the prefix at the current revision.</li>
 *   <li>Confirm the role cannot write config ({@code <prefix>routes/}), without writing anything.</li>
 *   <li>Open a watch on the prefix from revision + 1; optionally wait for the first event.</li>
 * </ol>
 *
 * <p>Prints one JSON line per stage to stdout. Exit 0 when every check passes, 1 otherwise, 2 on usage errors.
 * Never prints credentials.
 */
public final class EtcdCheckCommand {

    private static final String USAGE = """
            usage: gateway check-etcd [options]
              --endpoints <url,...>     etcd endpoints            (env GW_ETCD_ENDPOINTS)
              --cacert <file>           CA bundle (PEM)           (env GW_ETCD_CA)
              --user <name>             etcd user                 (env GW_ETCD_USER)
              --password-file <file>    file holding the password (env GW_ETCD_PASSWORD_FILE)
              --prefix <prefix>         config prefix, e.g. /gw/prod/ (env GW_ETCD_PREFIX)
              --expect-event            wait for one watch event under the prefix
              --timeout-seconds <n>     overall deadline (default 30)
            """;

    private EtcdCheckCommand() {
    }

    public static int run(String[] args, Map<String, String> env, PrintStream out) {
        Map<String, String> opts;
        try {
            opts = parse(args);
        } catch (IllegalArgumentException e) {
            out.println(USAGE);
            return 2;
        }
        if (opts.containsKey("help")) {
            out.println(USAGE);
            return 0;
        }
        EtcdClientConfig config;
        String prefix;
        int timeoutSeconds;
        try {
            prefix = required(opts, env, "prefix", "GW_ETCD_PREFIX");
            if (!prefix.endsWith("/")) {
                throw new IllegalArgumentException("--prefix must end with '/'");
            }
            timeoutSeconds = Integer.parseInt(opts.getOrDefault("timeout-seconds", "30"));
            config = config(opts, env, Duration.ofSeconds(Math.min(10, timeoutSeconds)));
        } catch (IllegalArgumentException e) {
            out.println(line("config", false).put("error", e.getMessage()));
            return 2;
        }
        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1).setWorkerPoolSize(1));
        EtcdHttpClient client = EtcdHttpClient.create(vertx, config);
        long started = System.nanoTime();
        try {
            boolean ok = check(client, prefix, opts.containsKey("expect-event"), out)
                    .toCompletionStage().toCompletableFuture().get(timeoutSeconds, TimeUnit.SECONDS);
            out.println(line("summary", ok)
                    .put("endpoint", client.currentEndpoint().toString())
                    .put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            return ok ? 0 : 1;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            out.println(line("summary", false).put("error", describe(cause)));
            return 1;
        } catch (TimeoutException e) {
            out.println(line("summary", false).put("error", "timed out after " + timeoutSeconds + "s"));
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } finally {
            client.close();
            vertx.close();
        }
    }

    private static Future<Boolean> check(EtcdHttpClient client, String prefix, boolean expectEvent, PrintStream out) {
        return client.authenticate()
                .onSuccess(v -> out.println(line("authenticate", true)))
                .compose(v -> client.countPrefix(prefix))
                .compose(range -> {
                    out.println(line("range", true).put("revision", range.revision()).put("keys", range.count()));
                    return client.canWrite(prefix + "routes/__gateway_write_probe").map(canWrite -> {
                        // A gateway that can write config breaks invariant 4: fail the check.
                        out.println(line("write-denied", !canWrite));
                        return new long[] {range.revision(), canWrite ? 0 : 1};
                    });
                })
                .compose(state -> watch(client, prefix, state[0] + 1, expectEvent, out)
                        .map(watchOk -> watchOk && state[1] == 1));
    }

    private static Future<Boolean> watch(EtcdHttpClient client, String prefix, long from, boolean expectEvent,
            PrintStream out) {
        Promise<Boolean> done = Promise.promise();
        EtcdHttpClient.Watch[] handle = new EtcdHttpClient.Watch[1];
        handle[0] = client.watchPrefix(prefix, from, true, r -> {
            if (r.created()) {
                out.println(line("watch-created", true).put("fromRevision", from));
                if (!expectEvent) {
                    done.tryComplete(true);
                    handle[0].cancel();
                }
            } else if (!r.events().isEmpty()) {
                WatchEvent e = r.events().getFirst();
                out.println(line("watch-event", true).put("type", e.type().name()).put("key", e.kv().key())
                        .put("modRevision", e.kv().modRevision()));
                done.tryComplete(true);
                handle[0].cancel();
            }
        }, err -> {
            if (!done.future().isComplete()) {
                done.tryFail(err);
            }
        });
        return done.future();
    }

    private static ObjectNode line(String stage, boolean ok) {
        return EtcdJson.MAPPER.createObjectNode().put("check", "etcd").put("stage", stage).put("ok", ok);
    }

    private static String describe(Throwable t) {
        if (t instanceof EtcdException e) {
            return "etcd code " + e.code() + ": " + e.getMessage();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static EtcdClientConfig config(Map<String, String> opts, Map<String, String> env, Duration timeout) {
        List<URI> endpoints = Arrays.stream(required(opts, env, "endpoints", "GW_ETCD_ENDPOINTS").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(URI::create).toList();
        Optional<Path> ca = optional(opts, env, "cacert", "GW_ETCD_CA").map(Path::of);
        Optional<String> user = optional(opts, env, "user", "GW_ETCD_USER");
        Optional<Password> password = optional(opts, env, "password-file", "GW_ETCD_PASSWORD_FILE")
                .map(f -> Password.of(readSecret(Path.of(f))));
        return new EtcdClientConfig(endpoints, ca, Optional.empty(), Optional.empty(), user, password, timeout);
    }

    private static String readSecret(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read password file " + file, e);
        }
    }

    private static String required(Map<String, String> opts, Map<String, String> env, String opt, String var) {
        return optional(opts, env, opt, var)
                .orElseThrow(() -> new IllegalArgumentException("--" + opt + " or " + var + " is required"));
    }

    private static Optional<String> optional(Map<String, String> opts, Map<String, String> env, String opt,
            String var) {
        return Optional.ofNullable(opts.get(opt)).or(() -> Optional.ofNullable(env.get(var)))
                .filter(s -> !s.isBlank());
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--help", "-h" -> out.put("help", "");
                case "--expect-event" -> out.put("expect-event", "");
                case "--endpoints", "--cacert", "--user", "--password-file", "--prefix", "--timeout-seconds" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(a);
                    }
                    out.put(a.substring(2), args[++i]);
                }
                default -> throw new IllegalArgumentException(a);
            }
        }
        return out;
    }
}
