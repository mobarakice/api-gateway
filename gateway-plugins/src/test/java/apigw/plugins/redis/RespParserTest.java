package apigw.plugins.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vertx.core.buffer.Buffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RespParserTest {

    private static List<Reply> parse(String... chunks) {
        List<Reply> out = new ArrayList<>();
        RespParser p = new RespParser(out::add);
        for (String c : chunks) {
            p.feed(Buffer.buffer(c));
        }
        return out;
    }

    @Test
    void parsesEveryTypeAcrossArbitraryChunkBoundaries() {
        String all = "+OK\r\n-ERR boom\r\n:42\r\n$5\r\nhello\r\n$-1\r\n*3\r\n:1\r\n$2\r\nab\r\n*1\r\n+x\r\n*-1\r\n";
        List<Reply> whole = parse(all);
        assertThat(whole).hasSize(7);
        assertThat(whole.get(0)).isEqualTo(new Reply.Simple("OK"));
        assertThat(whole.get(1)).isEqualTo(new Reply.Error("ERR boom"));
        assertThat(whole.get(2)).isEqualTo(new Reply.Int(42));
        assertThat(((Reply.Bulk) whole.get(3)).value().toString()).isEqualTo("hello");
        assertThat(((Reply.Bulk) whole.get(4)).value()).isNull();
        assertThat(((Reply.Array) whole.get(5)).items()).hasSize(3);
        assertThat(((Reply.Array) whole.get(6)).items()).isNull();
        // Byte by byte gives the same replies in the same order.
        String[] bytes = all.chars().mapToObj(c -> String.valueOf((char) c)).toArray(String[]::new);
        assertThat(parse(bytes).toString()).isEqualTo(whole.toString());
    }

    @Test
    void largeBulkInManyChunks() {
        String v = "x".repeat(200_000);
        String frame = "$" + v.length() + "\r\n" + v + "\r\n";
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < frame.length(); i += 4096) {
            chunks.add(frame.substring(i, Math.min(frame.length(), i + 4096)));
        }
        List<Reply> r = parse(chunks.toArray(String[]::new));
        assertThat(r).hasSize(1);
        assertThat(((Reply.Bulk) r.getFirst()).value().length()).isEqualTo(200_000);
    }

    @Test
    void rejectsProtocolViolations() {
        assertThatThrownBy(() -> parse("?x\r\n")).isInstanceOf(RespParser.ProtocolException.class);
        assertThatThrownBy(() -> parse("$3\r\nabcd\r\n")).isInstanceOf(RespParser.ProtocolException.class);
        assertThatThrownBy(() -> parse("$999999999999\r\n")).isInstanceOf(RespParser.ProtocolException.class);
        assertThatThrownBy(() -> parse(":12a\r\n")).isInstanceOf(RespParser.ProtocolException.class);
        assertThatThrownBy(() -> parse("*1\r\n".repeat(10) + ":1\r\n"))
                .isInstanceOf(RespParser.ProtocolException.class);
    }

    @Test
    void encodesCommandsAsBulkArrays() {
        assertThat(RedisClient.encode("SET", "k", Buffer.buffer(new byte[] {0, 13, 10})).getBytes())
                .isEqualTo(concat("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$3\r\n", new byte[] {0, 13, 10}, "\r\n"));
    }

    private static byte[] concat(String a, byte[] b, String c) {
        return Buffer.buffer(a).appendBytes(b).appendString(c).getBytes();
    }
}
