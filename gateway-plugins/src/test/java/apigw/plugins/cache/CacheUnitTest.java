package apigw.plugins.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

class CacheUnitTest {

    @Test
    void directivesAreMatchedAsWholeTokens() {
        assertThat(CachePluginFactory.containsDirective("public, no-store", "no-store")).isTrue();
        assertThat(CachePluginFactory.containsDirective("No-Cache", "no-cache")).isTrue();
        assertThat(CachePluginFactory.containsDirective("no-cache-ish", "no-cache")).isFalse();
        assertThat(CachePluginFactory.containsDirective("x-private", "private")).isFalse();
        assertThat(CachePluginFactory.containsDirective("private=\"set-cookie\"", "private")).isTrue();
        assertThat(CachePluginFactory.directiveSeconds("public, max-age=60, s-maxage=30", "s-maxage")).isEqualTo(30);
        assertThat(CachePluginFactory.directiveSeconds("s-maxage=30", "max-age")).isEqualTo(-1);
        assertThat(CachePluginFactory.directiveSeconds("max-age=abc", "max-age")).isEqualTo(-1);
        assertThat(CachePluginFactory.directiveSeconds("max-age=99999999999", "max-age")).isEqualTo(-1);
    }

    @Test
    void entriesRoundTripThroughTheL2CodecAndRejectGarbage() {
        CachedResponse r = new CachedResponse("k\nroute", 200, new String[] {"content-type", "etag"},
                new String[] {"application/json", "\"v1\""}, Buffer.buffer("{\"a\":1}"), 1, 2, 3,
                new String[] {"t1"});
        CachedResponse back = CachedResponse.decode(r.encode());
        assertThat(back).isNotNull();
        assertThat(back.key()).isEqualTo(r.key());
        assertThat(back.body().toString()).isEqualTo("{\"a\":1}");
        assertThat(back.names()).containsExactly("content-type", "etag");
        assertThat(back.tags()).containsExactly("t1");
        assertThat(back.staleUntilMs()).isEqualTo(3);
        assertThat(CachedResponse.decode(Buffer.buffer("nope"))).isNull();
        Buffer truncated = r.encode();
        assertThat(CachedResponse.decode(truncated.getBuffer(0, truncated.length() - 2))).isNull();
        assertThat(ResponseCache.l2Key("a")).startsWith("gwc:").hasSize(68);
    }
}
