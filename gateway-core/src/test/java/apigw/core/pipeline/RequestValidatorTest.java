package apigw.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import apigw.core.snapshot.CompiledSettings;
import apigw.model.Cidr;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RequestValidatorTest {

    @Test
    void acceptsOrdinaryPaths() {
        for (String p : List.of("/", "/a", "/a/", "/a/b.c", "/a/%20x", "/a/..b", "/a/.well-known", "/~user",
                "/a/b;c=d", "/%E2%82%AC")) {
            assertThat(RequestValidator.validPath(p)).as(p).isTrue();
        }
    }

    @Test
    void rejectsAmbiguousPaths() {
        for (String p : List.of("", "a", "//a", "/a//b", "/.", "/a/.", "/a/./b", "/..", "/a/../b", "/a/%2e%2e/b",
                "/a/%2E", "/a%2fb", "/a%2Fb", "/a%5cb", "/a\\b", "/a%00", "/a%0a", "/a b", "/a%", "/a%g1", "/a%1",
                "/a#b", "/é")) {
            assertThat(RequestValidator.validPath(p)).as(p).isFalse();
        }
    }

    @Test
    void parsesContentLengthStrictly() {
        assertThat(RequestValidator.parseLength("0")).isZero();
        assertThat(RequestValidator.parseLength("123")).isEqualTo(123);
        assertThat(RequestValidator.parseLength("+1")).isNegative();
        assertThat(RequestValidator.parseLength(" 1")).isNegative();
        assertThat(RequestValidator.parseLength("1,1")).isNegative();
        assertThat(RequestValidator.parseLength("")).isNegative();
        assertThat(RequestValidator.parseLength("9999999999999999999")).isNegative();
    }

    @Test
    void clientIpSkipsTrustedHopsFromTheRight() {
        CompiledSettings s = CompiledSettings.of(List.of(Cidr.parse("10.0.0.0/8")), Set.of(), "", 1);
        assertThat(ExchangeImpl.clientFromChain(s, "203.0.113.7, 10.1.1.1", "10.0.0.1")).isEqualTo("203.0.113.7");
        assertThat(ExchangeImpl.clientFromChain(s, "1.1.1.1, 203.0.113.7, 10.1.1.1", "10.0.0.1"))
                .isEqualTo("203.0.113.7");
        assertThat(ExchangeImpl.clientFromChain(s, "10.9.9.9", "10.0.0.1")).isEqualTo("10.9.9.9");
        assertThat(ExchangeImpl.clientFromChain(s, "garbage, 10.1.1.1", "10.0.0.1")).isEqualTo("garbage");
    }
}
