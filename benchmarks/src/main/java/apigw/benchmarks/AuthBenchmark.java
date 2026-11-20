package apigw.benchmarks;

import apigw.model.ApiKeyHash;
import apigw.plugins.jwt.JwtAlgorithm;
import apigw.plugins.jwt.JwtVerifier;
import apigw.plugins.jwt.Jwks;
import apigw.plugins.jwt.KeySet;
import apigw.plugins.testing.TestJwt;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Per-request cost of authentication: full JWT verification (parse, header checks, signature, claims)
 * per algorithm family, and the API-key check (salted SHA-256, constant-time compare).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class AuthBenchmark {

    private static final String ISS = "https://idp.bench";
    private static final JwtVerifier.Revocations NONE = (jti, iss, now) -> false;

    private JwtVerifier asymmetric;
    private JwtVerifier hmac;
    private String rs256;
    private String es256;
    private String eddsa;
    private String hs256;
    private Instant now;
    private ApiKeyHash.Parsed stored;
    private String secret;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        KeyPair rsa = TestJwt.rsa();
        KeyPair ec = TestJwt.ec256();
        KeyPair ed = TestJwt.ed25519();
        byte[] key = "bench-secret-bench-secret-bench-secret!!".getBytes(StandardCharsets.US_ASCII);
        KeySet keys = Jwks.parse(new ObjectMapper().readTree(TestJwt.jwks(TestJwt.jwk(rsa, "r", "RS256"),
                TestJwt.jwk(ec, "e", "ES256"), TestJwt.jwk(ed, "d", "EdDSA")))).keys();
        asymmetric = new JwtVerifier(List.of(new JwtVerifier.Issuer(ISS, Set.of("api"),
                EnumSet.of(JwtAlgorithm.RS256, JwtAlgorithm.ES256, JwtAlgorithm.EDDSA), () -> keys, 0)), 60, 8192);
        KeySet hk = Jwks.hmac(key);
        hmac = new JwtVerifier(List.of(new JwtVerifier.Issuer(ISS, Set.of("api"), EnumSet.of(JwtAlgorithm.HS256),
                () -> hk, 0)), 60, 8192);
        now = Instant.now();
        String claims = "{\"iss\":\"" + ISS + "\",\"sub\":\"user-123\",\"aud\":\"api\",\"iat\":"
                + (now.getEpochSecond() - 5) + ",\"exp\":" + (now.getEpochSecond() + 3600)
                + ",\"roles\":[\"reader\",\"writer\"],\"tid\":\"tenant-a\"}";
        rs256 = TestJwt.sign(TestJwt.header("RS256", "r"), claims, "RS256", rsa.getPrivate());
        es256 = TestJwt.sign(TestJwt.header("ES256", "e"), claims, "ES256", ec.getPrivate());
        eddsa = TestJwt.sign(TestJwt.header("EdDSA", "d"), claims, "EdDSA", ed.getPrivate());
        hs256 = TestJwt.sign(TestJwt.header("HS256", null), claims, "HS256", key);
        secret = ApiKeyHash.newSecret();
        stored = ApiKeyHash.parse(ApiKeyHash.hash(secret));
        for (String t : List.of(rs256, es256, eddsa)) {
            if (!(asymmetric.verify(t, now, NONE) instanceof JwtVerifier.Valid)) {
                throw new IllegalStateException("benchmark token does not verify");
            }
        }
        if (!(hmac.verify(hs256, now, NONE) instanceof JwtVerifier.Valid)) {
            throw new IllegalStateException("benchmark token does not verify");
        }
    }

    @Benchmark
    public Object jwtRs256() {
        return asymmetric.verify(rs256, now, NONE);
    }

    @Benchmark
    public Object jwtEs256() {
        return asymmetric.verify(es256, now, NONE);
    }

    @Benchmark
    public Object jwtEdDsa() {
        return asymmetric.verify(eddsa, now, NONE);
    }

    @Benchmark
    public Object jwtHs256() {
        return hmac.verify(hs256, now, NONE);
    }

    @Benchmark
    public boolean apiKey() {
        return ApiKeyHash.matches(stored, secret);
    }
}
