package apigw.config.etcd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Codec tests against payload shapes produced by etcd's grpc-gateway (int64 as strings, defaults omitted). */
class EtcdJsonTest {

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode json(String s) {
        return EtcdJson.parse(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void prefixEndIncrementsLastByte() {
        assertThat(EtcdJson.prefixEnd("/gw/prod/")).isEqualTo("/gw/prod0".getBytes(StandardCharsets.UTF_8));
        assertThat(EtcdJson.prefixEnd("")).containsExactly(0);
    }

    @Test
    void parsesRangeResponse() {
        RangeResult r = EtcdJson.range(json("""
                {"header":{"cluster_id":"1","revision":"42"}, \
                 "kvs":[{"key":"%s","create_revision":"5","mod_revision":"41","version":"3","value":"%s"}], \
                 "count":"1"}""".formatted(b64("/gw/t/routes/a"), b64("{\"id\":\"a\"}"))));
        assertThat(r.revision()).isEqualTo(42);
        assertThat(r.count()).isEqualTo(1);
        assertThat(r.more()).isFalse();
        KeyValue kv = r.kvs().getFirst();
        assertThat(kv.key()).isEqualTo("/gw/t/routes/a");
        assertThat(kv.valueUtf8()).isEqualTo("{\"id\":\"a\"}");
        assertThat(kv.modRevision()).isEqualTo(41);
        assertThat(kv.lease()).isZero();
    }

    @Test
    void parsesEmptyRangeWithOmittedDefaults() {
        RangeResult r = EtcdJson.range(json("{\"header\":{\"revision\":\"7\"}}"));
        assertThat(r.revision()).isEqualTo(7);
        assertThat(r.kvs()).isEmpty();
        assertThat(r.count()).isZero();
    }

    @Test
    void parsesWatchEventsAndDefaultsTypeToPut() {
        WatchResponse w = EtcdJson.watch(json("""
                {"header":{"revision":"10"},"events":[ \
                  {"kv":{"key":"%s","mod_revision":"10","value":"%s"}}, \
                  {"type":"DELETE","kv":{"key":"%s","mod_revision":"10"}}]}"""
                .formatted(b64("/k1"), b64("v"), b64("/k2"))));
        assertThat(w.events()).extracting(WatchEvent::type)
                .containsExactly(WatchEvent.Type.PUT, WatchEvent.Type.DELETE);
        assertThat(w.events().get(1).kv().value()).isEmpty();
        assertThat(w.progressNotify()).isFalse();
    }

    @Test
    void recognisesCreatedProgressAndCompaction() {
        assertThat(EtcdJson.watch(json("{\"header\":{\"revision\":\"3\"},\"created\":true}")).created()).isTrue();
        assertThat(EtcdJson.watch(json("{\"header\":{\"revision\":\"9\"}}")).progressNotify()).isTrue();
        WatchResponse compacted = EtcdJson.watch(json(
                "{\"header\":{\"revision\":\"9\"},\"canceled\":true,\"compact_revision\":\"5\"}"));
        assertThat(compacted.canceled()).isTrue();
        assertThat(compacted.compactRevision()).isEqualTo(5);
    }

    @Test
    void mapsErrors() {
        EtcdException unary = EtcdJson.unaryError(json("{\"error\":\"etcdserver: permission denied\","
                + "\"code\":7,\"message\":\"etcdserver: permission denied\"}"));
        assertThat(unary).isNotNull();
        assertThat(unary.permissionDenied()).isTrue();
        EtcdException token = EtcdJson.streamError(json(
                "{\"error\":{\"grpc_code\":3,\"http_code\":400,\"message\":\"etcdserver: invalid auth token\"}}"));
        assertThat(token).isNotNull();
        assertThat(token.invalidToken()).isTrue();
        assertThat(EtcdJson.unaryError(json("{\"header\":{}}"))).isNull();
    }

    @Test
    void rejectsMalformedPayloads() {
        assertThatThrownBy(() -> EtcdJson.parse("{not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EtcdException.class);
        assertThatThrownBy(() -> EtcdJson.range(json("{\"header\":{\"revision\":\"x\"}}")))
                .isInstanceOf(EtcdException.class);
    }

    @Test
    void passwordNeverRendersInToString() {
        assertThat(Password.of("hunter2").toString()).doesNotContain("hunter2");
    }
}
