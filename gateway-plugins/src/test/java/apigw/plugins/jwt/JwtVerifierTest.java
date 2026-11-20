package apigw.plugins.jwt;

import static apigw.plugins.testing.TestJwt.header;
import static apigw.plugins.testing.TestJwt.sign;
import static org.assertj.core.api.Assertions.assertThat;

import apigw.plugins.jwt.JwtVerifier.Invalid;
import apigw.plugins.jwt.JwtVerifier.Reason;
import apigw.plugins.testing.TestJwt;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JwtVerifierTest {

    private static final String ISS = "https://idp.example.com";
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final long T = NOW.getEpochSecond();
    private static final KeyPair RSA = TestJwt.rsa();
    private static final KeyPair EC = TestJwt.ec256();
    private static final KeyPair ED = TestJwt.ed25519();
    private static final KeyPair OTHER_RSA = TestJwt.rsa();
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final JwtVerifier.Revocations NONE_REVOKED = (jti, iss, now) -> false;

    private static KeySet keys() throws Exception {
        String jwks = TestJwt.jwks(TestJwt.jwk(RSA, "rsa1", null), TestJwt.jwk(EC, "ec1", "ES256"),
                TestJwt.jwk(ED, "ed1", null));
        Jwks.Parsed p = Jwks.parse(new ObjectMapper().readTree(jwks));
        assertThat(p.problems()).isEmpty();
        return p.keys();
    }

    private static JwtVerifier verifier(Set<JwtAlgorithm> algs) throws Exception {
        KeySet k = keys();
        return new JwtVerifier(List.of(new JwtVerifier.Issuer(ISS, Set.of("orders"), algs, () -> k, 0)), 60, 8192);
    }

    private static JwtVerifier asymmetric() throws Exception {
        return verifier(EnumSet.of(JwtAlgorithm.RS256, JwtAlgorithm.PS256, JwtAlgorithm.ES256, JwtAlgorithm.EDDSA));
    }

    private static String claims(String extra) {
        return "{\"iss\":\"" + ISS + "\",\"sub\":\"alice\",\"aud\":\"orders\",\"iat\":" + (T - 10) + ",\"exp\":"
                + (T + 300) + extra + "}";
    }

    private static Object reason(JwtVerifier v, String token) {
        JwtVerifier.Result r = v.verify(token, NOW, NONE_REVOKED);
        return r instanceof Invalid i ? i.reason() : r.getClass().getSimpleName();
    }

    @Test
    void acceptsEveryPinnedAlgorithmWithItsKeyFamily() throws Exception {
        JwtVerifier v = asymmetric();
        assertThat(reason(v, sign(header("RS256", "rsa1"), claims(""), "RS256", RSA.getPrivate()))).isEqualTo("Valid");
        assertThat(reason(v, sign(header("PS256", "rsa1"), claims(""), "PS256", RSA.getPrivate()))).isEqualTo("Valid");
        assertThat(reason(v, sign(header("ES256", "ec1"), claims(""), "ES256", EC.getPrivate()))).isEqualTo("Valid");
        assertThat(reason(v, sign(header("EdDSA", "ed1"), claims(""), "EdDSA", ED.getPrivate()))).isEqualTo("Valid");
        JwtVerifier hs = new JwtVerifier(List.of(new JwtVerifier.Issuer(ISS, Set.of("orders"),
                EnumSet.of(JwtAlgorithm.HS256), () -> Jwks.hmac(SECRET), 0)), 60, 8192);
        assertThat(reason(hs, sign(header("HS256", null), claims(""), "HS256", SECRET))).isEqualTo("Valid");
    }

    @Test
    void algorithmConfusionAndNoneAreRejected() throws Exception {
        JwtVerifier v = asymmetric();
        // Classic HS/RS confusion: HMAC with the RSA public key (as an attacker would find it) as the secret.
        byte[] publicKeyAsSecret = RSA.getPublic().getEncoded();
        assertThat(reason(v, sign(header("HS256", "rsa1"), claims(""), "HS256", publicKeyAsSecret)))
                .isEqualTo(Reason.ALGORITHM_NOT_ALLOWED);
        // Even an issuer that (wrongly) allowed both families could not use an RSA JWK as an HMAC key.
        KeySet k = keys();
        JwtVerifier both = new JwtVerifier(List.of(new JwtVerifier.Issuer(ISS, Set.of("orders"),
                EnumSet.of(JwtAlgorithm.RS256, JwtAlgorithm.HS256), () -> k, 0)), 60, 8192);
        assertThat(reason(both, sign(header("HS256", "rsa1"), claims(""), "HS256", publicKeyAsSecret)))
                .isEqualTo(Reason.NO_SUITABLE_KEY);
        // alg none, in every spelling, with or without a signature part.
        for (String none : List.of("none", "None", "NONE")) {
            String unsigned = TestJwt.b64(header(none, "rsa1")) + "." + TestJwt.b64(claims("")) + ".";
            assertThat(reason(v, unsigned)).isEqualTo(Reason.MALFORMED);
            assertThat(reason(v, unsigned + "AAAA")).isEqualTo(Reason.ALGORITHM_NOT_ALLOWED);
        }
        // An algorithm the issuer did not pin.
        JwtVerifier rsOnly = verifier(EnumSet.of(JwtAlgorithm.RS256));
        assertThat(reason(rsOnly, sign(header("ES256", "ec1"), claims(""), "ES256", EC.getPrivate())))
                .isEqualTo(Reason.ALGORITHM_NOT_ALLOWED);
        // A JWK pinned to ES256 cannot verify another algorithm, and a key of the wrong family is never used.
        assertThat(reason(v, sign(header("RS256", "ec1"), claims(""), "RS256", RSA.getPrivate())))
                .isEqualTo(Reason.NO_SUITABLE_KEY);
    }

    @Test
    void signatureMustMatch() throws Exception {
        JwtVerifier v = asymmetric();
        String good = sign(header("RS256", "rsa1"), claims(""), "RS256", RSA.getPrivate());
        String[] p = good.split("\\.");
        String tampered = p[0] + "." + TestJwt.b64(claims(",\"admin\":true")) + "." + p[2];
        assertThat(reason(v, tampered)).isEqualTo(Reason.BAD_SIGNATURE);
        assertThat(reason(v, sign(header("RS256", "rsa1"), claims(""), "RS256", OTHER_RSA.getPrivate())))
                .isEqualTo(Reason.BAD_SIGNATURE);
        // Header-supplied key locations are ignored: the attacker's own key in jku/jwk changes nothing.
        String jku = "{\"alg\":\"RS256\",\"kid\":\"rsa1\",\"jku\":\"https://evil.example/jwks\","
                + "\"jwk\":" + TestJwt.jwk(OTHER_RSA, "rsa1", null) + "}";
        assertThat(reason(v, sign(jku, claims(""), "RS256", OTHER_RSA.getPrivate()))).isEqualTo(Reason.BAD_SIGNATURE);
        // ECDSA signatures must have the exact P1363 length.
        String es = sign(header("ES256", "ec1"), claims(""), "ES256", EC.getPrivate());
        assertThat(reason(v, es + "AA")).isEqualTo(Reason.BAD_SIGNATURE);
    }

    @Test
    void timeAudienceAndIssuerClaims() throws Exception {
        JwtVerifier v = asymmetric();
        String h = header("RS256", "rsa1");
        Object k = RSA.getPrivate();
        String base = "{\"iss\":\"" + ISS + "\",\"aud\":\"orders\"";
        assertThat(reason(v, sign(h, base + ",\"exp\":" + (T - 61) + "}", "RS256", k))).isEqualTo(Reason.EXPIRED);
        assertThat(reason(v, sign(h, base + ",\"exp\":" + (T - 59) + "}", "RS256", k))).isEqualTo("Valid");
        assertThat(reason(v, sign(h, base + "}", "RS256", k))).isEqualTo(Reason.MISSING_EXP);
        assertThat(reason(v, sign(h, base + ",\"exp\":\"" + (T + 60) + "\"}", "RS256", k)))
                .isEqualTo(Reason.MISSING_EXP);
        assertThat(reason(v, sign(h, claims(",\"nbf\":" + (T + 120)), "RS256", k))).isEqualTo(Reason.NOT_YET_VALID);
        assertThat(reason(v, sign(h, "{\"iss\":\"" + ISS + "\",\"aud\":\"orders\",\"iat\":" + (T + 120) + ",\"exp\":"
                + (T + 600) + "}", "RS256", k))).isEqualTo(Reason.ISSUED_IN_FUTURE);
        assertThat(reason(v, sign(h, claims("").replace("\"aud\":\"orders\"", "\"aud\":\"billing\""), "RS256", k)))
                .isEqualTo(Reason.WRONG_AUDIENCE);
        assertThat(reason(v, sign(h, claims("").replace("\"aud\":\"orders\"", "\"aud\":[\"billing\",\"orders\"]"),
                "RS256", k))).isEqualTo("Valid");
        assertThat(reason(v, sign(h, claims("").replace(",\"aud\":\"orders\"", ""), "RS256", k)))
                .isEqualTo(Reason.WRONG_AUDIENCE);
        assertThat(reason(v, sign(h, claims("").replace(ISS, "https://evil.example.com"), "RS256", k)))
                .isEqualTo(Reason.UNKNOWN_ISSUER);
        assertThat(reason(v, sign(h, claims("").replace(ISS, ISS + "/"), "RS256", k))).isEqualTo(Reason.UNKNOWN_ISSUER);
    }

    @Test
    void keyIdsCriticalHeadersAndShape() throws Exception {
        JwtVerifier v = asymmetric();
        assertThat(reason(v, sign(header("RS256", "rotated"), claims(""), "RS256", RSA.getPrivate())))
                .isEqualTo("UnknownKey");
        // No kid: accepted only when exactly one key fits the algorithm.
        assertThat(reason(v, sign(header("RS256", null), claims(""), "RS256", RSA.getPrivate()))).isEqualTo("Valid");
        assertThat(reason(v, sign("{\"alg\":\"RS256\",\"kid\":\"rsa1\",\"crit\":[\"exp\"]}", claims(""), "RS256",
                RSA.getPrivate()))).isEqualTo(Reason.CRITICAL_HEADER);
        String good = sign(header("RS256", "rsa1"), claims(""), "RS256", RSA.getPrivate());
        assertThat(reason(v, good.substring(0, good.lastIndexOf('.')))).isEqualTo(Reason.MALFORMED);
        assertThat(reason(v, good + ".x")).isEqualTo(Reason.MALFORMED);
        assertThat(reason(v, good + "=")).isEqualTo(Reason.MALFORMED);
        assertThat(reason(v, "Bearer " + good)).isEqualTo(Reason.MALFORMED);
        assertThat(reason(v, "a".repeat(9000))).isEqualTo(Reason.MALFORMED);
        // Duplicate claims are ambiguous (which "sub" did the signer mean?): refused outright.
        String dup = sign(header("RS256", "rsa1"), claims(",\"sub\":\"admin\""), "RS256", RSA.getPrivate());
        assertThat(reason(v, dup)).isEqualTo(Reason.MALFORMED);
    }

    @Test
    void revokedJtiAndMaxAge() throws Exception {
        KeySet k = keys();
        JwtVerifier v = new JwtVerifier(List.of(new JwtVerifier.Issuer(ISS, Set.of("orders"),
                EnumSet.of(JwtAlgorithm.RS256), () -> k, 60)), 0, 8192);
        String token = sign(header("RS256", "rsa1"), claims(",\"jti\":\"t-1\""), "RS256", RSA.getPrivate());
        assertThat(v.verify(token, NOW, (jti, iss, now) -> "t-1".equals(jti) && ISS.equals(iss)))
                .isEqualTo(new Invalid(Reason.REVOKED));
        assertThat(v.verify(token, NOW, NONE_REVOKED)).isInstanceOf(JwtVerifier.Valid.class);
        String old = sign(header("RS256", "rsa1"), claims("").replace("\"iat\":" + (T - 10), "\"iat\":" + (T - 3600)),
                "RS256", RSA.getPrivate());
        assertThat(v.verify(old, NOW, NONE_REVOKED)).isEqualTo(new Invalid(Reason.TOO_OLD));
    }

    @Test
    void cachedSignatureNeverOutlivesItsKeyAndClaimsAreAlwaysRechecked() throws Exception {
        java.util.concurrent.atomic.AtomicReference<KeySet> current = new java.util.concurrent.atomic.AtomicReference<>(
                keys());
        JwtVerifier v = new JwtVerifier(List.of(new JwtVerifier.Issuer(ISS, Set.of("orders"),
                EnumSet.of(JwtAlgorithm.RS256), current::get, 0)), 0, 8192);
        String token = sign(header("RS256", "rsa1"), claims(",\"jti\":\"j-9\""), "RS256", RSA.getPrivate());
        assertThat(v.verify(token, NOW, NONE_REVOKED)).isInstanceOf(JwtVerifier.Valid.class);
        // Same token, after expiry: the cached signature does not skip the time checks.
        assertThat(v.verify(token, NOW.plusSeconds(3600), NONE_REVOKED)).isEqualTo(new Invalid(Reason.EXPIRED));
        // Revoked later: still re-checked.
        assertThat(v.verify(token, NOW, (jti, iss, now) -> "j-9".equals(jti))).isEqualTo(new Invalid(Reason.REVOKED));
        // The IdP replaces the key behind kid rsa1: the cached result is bound to the old key.
        current.set(Jwks.parse(new ObjectMapper().readTree(TestJwt.jwks(TestJwt.jwk(OTHER_RSA, "rsa1", null))))
                .keys());
        assertThat(v.verify(token, NOW, NONE_REVOKED)).isEqualTo(new Invalid(Reason.BAD_SIGNATURE));
    }

    @Test
    void verifiedCacheIsBounded() {
        VerifiedCache c = new VerifiedCache(3);
        KeySet.Entry k = Jwks.hmac(SECRET).all().getFirst();
        for (int i = 0; i < 10; i++) {
            c.add("t" + i, k);
            assertThat(c.size()).isLessThanOrEqualTo(3);
        }
        assertThat(c.contains("t9", k)).isTrue();
        assertThat(c.contains("t9", Jwks.hmac(SECRET).all().getFirst())).isFalse();
    }

    @Test
    void jwksRefusesPrivateSymmetricWeakAndMismatchedKeys() throws Exception {
        String priv = "{\"kty\":\"RSA\",\"kid\":\"p\",\"n\":\"AQAB\",\"e\":\"AQAB\",\"d\":\"AQAB\"}";
        String oct = "{\"kty\":\"oct\",\"kid\":\"s\",\"k\":\"c2VjcmV0\"}";
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        String weak = TestJwt.jwk(g.generateKeyPair(), "weak", null);
        String wrongAlg = TestJwt.jwk(EC, "ec2", "RS256");
        String enc = TestJwt.jwk(RSA, "enc", null).replace("\"use\":\"sig\"", "\"use\":\"enc\"");
        Jwks.Parsed p = Jwks.parse(new ObjectMapper().readTree(TestJwt.jwks(priv, oct, weak, wrongAlg, enc,
                TestJwt.jwk(RSA, "ok", null))));
        assertThat(p.keys().all()).extracting(KeySet.Entry::kid).containsExactly("ok");
        assertThat(p.problems()).hasSize(4);
    }
}
