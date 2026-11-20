package apigw.plugins.jwt;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;

/**
 * Verifies JWS compact tokens (RFC 7515/7519) with JDK primitives. Order: shape and size, header,
 * issuer selection from the unverified {@code iss}, algorithm pinned for that issuer, key of the
 * matching family, signature, then time and audience claims. Nothing in the header can choose where
 * keys come from ({@code jku}, {@code x5u}, {@code jwk}, {@code x5c} are ignored) and {@code crit} is
 * refused. Stateless and thread-safe.
 */
public final class JwtVerifier {

    /** A rejection reason: a fixed code safe to log (never the token). */
    public enum Reason {
        MALFORMED, UNKNOWN_ISSUER, ALGORITHM_NOT_ALLOWED, CRITICAL_HEADER, UNKNOWN_KEY, NO_SUITABLE_KEY,
        BAD_SIGNATURE, EXPIRED, NOT_YET_VALID, ISSUED_IN_FUTURE, TOO_OLD, MISSING_EXP, WRONG_AUDIENCE, REVOKED,
        UNKNOWN_CONSUMER, TENANT_MISMATCH
    }

    /** Verification outcome. */
    public sealed interface Result {
    }

    /** Valid token. */
    public record Valid(Issuer issuer, JsonNode claims) implements Result {
    }

    /** Invalid token. */
    public record Invalid(Reason reason) implements Result {
    }

    /** The issuer's key set has no key with the token's kid: refreshing the keys may help. */
    public record UnknownKey(Issuer issuer) implements Result {
    }

    /**
     * Per-issuer rules.
     *
     * @param keys           current verification keys (read on every verification; may change when refreshed)
     * @param maxAgeSeconds  0 = no limit on {@code now - iat}
     */
    public record Issuer(String issuer, Set<String> audiences, Set<JwtAlgorithm> algorithms,
            java.util.function.Supplier<KeySet> keys, long maxAgeSeconds) {
        public Issuer {
            audiences = Set.copyOf(audiences);
            algorithms = Set.copyOf(algorithms);
        }
    }

    /** Checks the {@code jti} deny-list. */
    @FunctionalInterface
    public interface Revocations {
        boolean revoked(String jti, String issuer, Instant now);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();

    private final Map<String, Issuer> issuers;
    private final long skewSeconds;
    private final int maxTokenBytes;
    private final VerifiedCache verified = new VerifiedCache();

    public JwtVerifier(List<Issuer> issuers, long skewSeconds, int maxTokenBytes) {
        Map<String, Issuer> m = new java.util.HashMap<>();
        issuers.forEach(i -> m.put(i.issuer(), i));
        this.issuers = Map.copyOf(m);
        this.skewSeconds = skewSeconds;
        this.maxTokenBytes = maxTokenBytes;
    }

    public Result verify(String token, Instant now, Revocations revocations) {
        if (token == null || token.isEmpty() || token.length() > maxTokenBytes) {
            return new Invalid(Reason.MALFORMED);
        }
        int d1 = token.indexOf('.');
        int d2 = d1 < 0 ? -1 : token.indexOf('.', d1 + 1);
        if (d1 <= 0 || d2 <= d1 + 1 || d2 == token.length() - 1 || token.indexOf('.', d2 + 1) >= 0) {
            return new Invalid(Reason.MALFORMED);
        }
        JsonNode header = json(token, 0, d1);
        JsonNode claims = json(token, d1 + 1, d2);
        byte[] signature = decode(token, d2 + 1, token.length());
        if (header == null || claims == null || signature == null || !header.isObject() || !claims.isObject()) {
            return new Invalid(Reason.MALFORMED);
        }
        Issuer issuer = claims.path("iss").isTextual() ? issuers.get(claims.get("iss").textValue()) : null;
        if (issuer == null) {
            return new Invalid(Reason.UNKNOWN_ISSUER);
        }
        JwtAlgorithm alg = header.path("alg").isTextual()
                ? JwtAlgorithm.fromJose(header.get("alg").textValue()).orElse(null) : null;
        if (alg == null || !issuer.algorithms().contains(alg)) {
            return new Invalid(Reason.ALGORITHM_NOT_ALLOWED);
        }
        if (header.has("crit")) {
            return new Invalid(Reason.CRITICAL_HEADER);
        }
        JsonNode kidNode = header.get("kid");
        if (kidNode != null && !kidNode.isTextual()) {
            return new Invalid(Reason.MALFORMED);
        }
        String kid = kidNode == null ? null : kidNode.textValue();
        KeySet keys = issuer.keys().get();
        KeySet.Entry key = keys.find(kid, alg);
        if (key == null) {
            if (kid != null && !keys.hasKid(kid)) {
                return new UnknownKey(issuer);
            }
            return kid == null && keys.all().isEmpty() ? new UnknownKey(issuer) : new Invalid(Reason.NO_SUITABLE_KEY);
        }
        if (!verified.contains(token, key)) {
            if (!signatureValid(alg, key, token.substring(0, d2).getBytes(StandardCharsets.US_ASCII), signature)) {
                return new Invalid(Reason.BAD_SIGNATURE);
            }
            verified.add(token, key);
        }
        return claims(issuer, claims, now, revocations);
    }

    private Result claims(Issuer issuer, JsonNode claims, Instant now, Revocations revocations) {
        long t = now.getEpochSecond();
        JsonNode exp = claims.get("exp");
        if (exp == null || !exp.isNumber()) {
            return new Invalid(Reason.MISSING_EXP);
        }
        if (t > exp.asLong() + skewSeconds) {
            return new Invalid(Reason.EXPIRED);
        }
        JsonNode nbf = claims.get("nbf");
        if (nbf != null && (!nbf.isNumber() || t + skewSeconds < nbf.asLong())) {
            return new Invalid(Reason.NOT_YET_VALID);
        }
        JsonNode iat = claims.get("iat");
        if (iat != null && (!iat.isNumber() || iat.asLong() > t + skewSeconds)) {
            return new Invalid(Reason.ISSUED_IN_FUTURE);
        }
        if (issuer.maxAgeSeconds() > 0 && (iat == null || t - iat.asLong() > issuer.maxAgeSeconds() + skewSeconds)) {
            return new Invalid(Reason.TOO_OLD);
        }
        if (!audienceMatches(claims.get("aud"), issuer.audiences())) {
            return new Invalid(Reason.WRONG_AUDIENCE);
        }
        JsonNode jti = claims.get("jti");
        if (jti != null && jti.isTextual() && revocations.revoked(jti.textValue(), issuer.issuer(), now)) {
            return new Invalid(Reason.REVOKED);
        }
        return new Valid(issuer, claims);
    }

    private static boolean audienceMatches(JsonNode aud, Set<String> allowed) {
        if (aud == null) {
            return false;
        }
        if (aud.isTextual()) {
            return allowed.contains(aud.textValue());
        }
        if (aud.isArray()) {
            for (JsonNode a : aud) {
                if (a.isTextual() && allowed.contains(a.textValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean signatureValid(JwtAlgorithm alg, KeySet.Entry key, byte[] input, byte[] sig) {
        try {
            if (alg.family() == JwtAlgorithm.Family.HMAC) {
                Mac mac = Mac.getInstance(alg.jdkName());
                mac.init(key.key());
                // Constant-time comparison of the full MAC.
                return MessageDigest.isEqual(mac.doFinal(input), sig);
            }
            if (alg.size() > 0 && sig.length != alg.size()) {
                return false;
            }
            Signature s = Signature.getInstance(alg.jdkName());
            if (alg.pss() != null) {
                s.setParameter(alg.pss());
            }
            s.initVerify((PublicKey) key.key());
            s.update(input);
            return s.verify(sig);
        } catch (GeneralSecurityException | ClassCastException e) {
            // Malformed signatures (e.g. bad encodings) are just invalid.
            return false;
        }
    }

    private static JsonNode json(String token, int from, int to) {
        byte[] bytes = decode(token, from, to);
        if (bytes == null) {
            return null;
        }
        try {
            return MAPPER.readTree(bytes);
        } catch (IOException e) {
            return null;
        }
    }

    /** Strict base64url without padding. */
    private static byte[] decode(String token, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = token.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-'
                    || c == '_';
            if (!ok) {
                return null;
            }
        }
        try {
            return B64.decode(token.substring(from, to));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
