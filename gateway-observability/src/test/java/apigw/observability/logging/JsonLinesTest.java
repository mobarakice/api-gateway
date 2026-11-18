package apigw.observability.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

class JsonLinesTest {

    @Test
    void rendersOneEscapedJsonLine() {
        String line = JsonLines.render(0, Level.INFO, "a.B", "main", "q\"uote\\ \n\t\u0001 ", null);
        assertThat(line).isEqualTo("{\"ts\":\"1970-01-01T00:00:00Z\",\"level\":\"INFO\",\"logger\":\"a.B\","
                + "\"thread\":\"main\",\"msg\":\"q\\\"uote\\\\ \\n\\t\\u0001\\u2028\"}\n");
        assertThat(line.indexOf('\n')).isEqualTo(line.length() - 1);
    }

    @Test
    void includesExceptionTypeAndStack() {
        String line = JsonLines.render(0, Level.ERROR, "x", "t", "boom", new IllegalStateException("bad"));
        assertThat(line).contains("\"error\":\"java.lang.IllegalStateException\"").contains("\"stack\":\"java.lang");
    }

    @Test
    void parsesLevels() {
        assertThat(LogLevels.parse(null)).isEqualTo(Level.INFO.toInt());
        assertThat(LogLevels.parse("debug")).isEqualTo(Level.DEBUG.toInt());
        assertThat(LogLevels.parse("OFF")).isEqualTo(Integer.MAX_VALUE);
        assertThat(LogLevels.parse("nonsense")).isEqualTo(Level.INFO.toInt());
    }

    @Test
    void frameworkLoggersAreHeldAtWarn() {
        assertThat(LogLevels.threshold("io.netty.channel.X")).isGreaterThanOrEqualTo(Level.WARN.toInt());
        assertThat(LogLevels.threshold("apigw.app.GatewayMain"))
                .isEqualTo(LogLevels.parse(System.getenv("GW_LOG_LEVEL")));
    }

    @Test
    void fullQueueDropsInsteadOfBlocking() {
        LogSink sink = new LogSink(new ByteArrayOutputStream(), 2);
        sink.offer("a\n");
        sink.offer("b\n");
        long start = System.nanoTime();
        sink.offer("c\n");
        assertThat(System.nanoTime() - start).isLessThan(50_000_000L);
        assertThat(sink.droppedCount()).isEqualTo(1);
    }

    @Test
    void sinkWritesQueuedLinesInOrder() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LogSink sink = new LogSink(out, 16);
        sink.offer("one\n");
        sink.offer("two\n");
        sink.drainNowForTest();
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("one\ntwo\n");
    }

    @Test
    void awaitDrainedWaitsForTheWriterThread() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LogSink sink = LogSink.start(out, 1024);
        for (int i = 0; i < 500; i++) {
            sink.offer("line " + i + "\n");
        }
        assertThat(sink.awaitDrained(5_000)).isTrue();
        assertThat(out.toString(StandardCharsets.UTF_8).lines()).hasSize(500).endsWith("line 499");
    }

    @Test
    void slf4jBindsToThisProvider() {
        assertThat(LoggerFactory.getLogger("t")).isInstanceOf(JsonLogger.class);
    }
}
