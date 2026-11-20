package apigw.plugins.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;

/**
 * JWK Set parsing into JDK keys. Public keys only: {@code d} (private) members and symmetric
 * {@code oct} keys in a JWKS are refused — HMAC secrets come from a file, never from config or a
 * network document. Keys with {@code use} other than {@code sig} are skipped.
 */
public final class Jwks {

    /** RSA keys shorter than this are refused (NIST SP 800-131A). */
    static final int MIN_RSA_BITS = 2048;
    private static final byte[] ED25519_SPKI_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03,
        0x21, 0x00};

    private Jwks() {
    }

    /** Result of parsing: the usable keys plus per-key problems (reported or logged by the caller). */
    public record Parsed(KeySet keys, List<String> problems) {
    }

    public static Parsed parse(JsonNode jwks) {
        List<String> problems = new ArrayList<>();
        Map<String, KeySet.Entry> byKid = new HashMap<>();
        List<KeySet.Entry> all = new ArrayList<>();
        JsonNode keys = jwks == null ? null : jwks.get("keys");
        if (keys == null || !keys.isArray()) {
            problems.add("keys: must be an array");
            return new Parsed(KeySet.EMPTY, problems);
        }
        for (int i = 0; i < keys.size(); i++) {
            JsonNode k = keys.get(i);
            String where = "keys[" + i + "]";
            if (!k.isObject()) {
                problems.add(where + ": must be an object");
                continue;
            }
            String use = k.path("use").asText("sig");
            if (!"sig".equals(use)) {
                continue;
            }
            if (k.has("d") || k.has("p") || k.has("k")) {
                problems.add(where + ": private or symmetric key material is not accepted in a JWKS");
                continue;
            }
            try {
                KeySet.Entry e = entry(k);
                if (e.kid() != null && byKid.putIfAbsent(e.kid(), e) != null) {
                    problems.add(where + ": duplicate kid '" + e.kid() + "'");
                    continue;
                }
                all.add(e);
            } catch (IllegalArgumentException | GeneralSecurityException e) {
                problems.add(where + ": " + e.getMessage());
            }
        }
        return new Parsed(new KeySet(byKid, all), problems);
    }

    private static KeySet.Entry entry(JsonNode k) throws GeneralSecurityException {
        String kid = k.hasNonNull("kid") ? k.get("kid").asText() : null;
        JwtAlgorithm alg = null;
        if (k.hasNonNull("alg")) {
            alg = JwtAlgorithm.fromJose(k.get("alg").asText())
                    .orElseThrow(() -> new IllegalArgumentException("unsupported alg '" + k.get("alg").asText() + "'"));
        }
        String kty = k.path("kty").asText("");
        return switch (kty) {
            case "RSA" -> {
                BigInteger n = new BigInteger(1, b64(k, "n"));
                BigInteger e = new BigInteger(1, b64(k, "e"));
                PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
                if (((RSAPublicKey) key).getModulus().bitLength() < MIN_RSA_BITS) {
                    throw new IllegalArgumentException("RSA key shorter than " + MIN_RSA_BITS + " bits");
                }
                yield new KeySet.Entry(kid, JwtAlgorithm.Family.RSA, check(alg, JwtAlgorithm.Family.RSA), key, 0);
            }
            case "EC" -> {
                String crv = k.path("crv").asText("");
                String std = switch (crv) {
                    case "P-256" -> "secp256r1";
                    case "P-384" -> "secp384r1";
                    case "P-521" -> "secp521r1";
                    default -> throw new IllegalArgumentException("unsupported EC curve '" + crv + "'");
                };
                int bytes = "P-256".equals(crv) ? 32 : "P-384".equals(crv) ? 48 : 66;
                byte[] x = b64(k, "x");
                byte[] y = b64(k, "y");
                if (x.length != bytes || y.length != bytes) {
                    throw new IllegalArgumentException("EC coordinates must be " + bytes + " bytes for " + crv);
                }
                AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
                params.init(new ECGenParameterSpec(std));
                ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
                PublicKey key = KeyFactory.getInstance("EC").generatePublic(
                        new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), spec));
                yield new KeySet.Entry(kid, JwtAlgorithm.Family.EC, check(alg, JwtAlgorithm.Family.EC), key, bytes);
            }
            case "OKP" -> {
                if (!"Ed25519".equals(k.path("crv").asText(""))) {
                    throw new IllegalArgumentException("only OKP curve Ed25519 is supported");
                }
                byte[] x = b64(k, "x");
                if (x.length != 32) {
                    throw new IllegalArgumentException("Ed25519 x must be 32 bytes");
                }
                byte[] spki = new byte[ED25519_SPKI_PREFIX.length + 32];
                System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
                System.arraycopy(x, 0, spki, ED25519_SPKI_PREFIX.length, 32);
                PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
                yield new KeySet.Entry(kid, JwtAlgorithm.Family.OKP, check(alg, JwtAlgorithm.Family.OKP), key, 0);
            }
            default -> throw new IllegalArgumentException("unsupported kty '" + kty + "'");
        };
    }

    private static JwtAlgorithm check(JwtAlgorithm alg, JwtAlgorithm.Family family) {
        if (alg != null && alg.family() != family) {
            throw new IllegalArgumentException("alg " + alg.joseName() + " does not fit the key type");
        }
        return alg;
    }

    /** One HMAC secret as a key set (no kid: tokens may omit it). */
    public static KeySet hmac(byte[] secret) {
        KeySet.Entry e = new KeySet.Entry(null, JwtAlgorithm.Family.HMAC, null,
                new SecretKeySpec(secret, "HmacSHA256"), 0);
        return new KeySet(Map.of(), List.of(e));
    }

    private static byte[] b64(JsonNode k, String field) {
        JsonNode v = k.get(field);
        if (v == null || !v.isTextual() || v.textValue().isEmpty()) {
            throw new IllegalArgumentException("missing '" + field + "'");
        }
        try {
            return Base64.getUrlDecoder().decode(v.textValue());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + field + "' is not base64url", e);
        }
    }
}
