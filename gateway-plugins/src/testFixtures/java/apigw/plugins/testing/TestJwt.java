package apigw.plugins.testing;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Test-only JWS minting with JDK primitives (real signatures, no mocks), plus JWK export of the public
 * half. Deliberately able to produce hostile tokens (alg none, confusion, tampering) for the security
 * suite.
 */
public final class TestJwt {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private TestJwt() {
    }

    public static KeyPair rsa() {
        return generate("RSA", 2048);
    }

    public static KeyPair ec256() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair ed25519() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate(String alg, int bits) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance(alg);
            g.initialize(bits);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String b64(byte[] b) {
        return B64.encodeToString(b);
    }

    public static String b64(String s) {
        return b64(s.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code header.payload.signature}, signing with {@code alg} (JOSE name) and {@code key}. */
    public static String sign(String headerJson, String claimsJson, String alg, Object key) {
        String input = b64(headerJson) + "." + b64(claimsJson);
        return input + "." + b64(signature(alg, key, input.getBytes(StandardCharsets.US_ASCII)));
    }

    /** Standard header with alg and optional kid. */
    public static String header(String alg, String kid) {
        return kid == null ? "{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}"
                : "{\"alg\":\"" + alg + "\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
    }

    public static byte[] signature(String alg, Object key, byte[] input) {
        try {
            switch (alg) {
                case "HS256", "HS384", "HS512" -> {
                    String mac = "Hmac" + "SHA" + alg.substring(2);
                    Mac m = Mac.getInstance(mac);
                    m.init(new SecretKeySpec((byte[]) key, mac));
                    return m.doFinal(input);
                }
                case "none" -> {
                    return new byte[0];
                }
                default -> {
                    Signature s = switch (alg) {
                        case "RS256" -> Signature.getInstance("SHA256withRSA");
                        case "PS256" -> {
                            Signature p = Signature.getInstance("RSASSA-PSS");
                            p.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
                            yield p;
                        }
                        case "ES256" -> Signature.getInstance("SHA256withECDSAinP1363Format");
                        case "EdDSA" -> Signature.getInstance("Ed25519");
                        default -> throw new IllegalArgumentException("unsupported test alg " + alg);
                    };
                    s.initSign((PrivateKey) key);
                    s.update(input);
                    return s.sign();
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Public JWK JSON for an RSA, EC P-256 or Ed25519 key pair. */
    public static String jwk(KeyPair kp, String kid, String alg) {
        String common = "\"kid\":\"" + kid + "\",\"use\":\"sig\"" + (alg == null ? "" : ",\"alg\":\"" + alg + "\"");
        if (kp.getPublic() instanceof RSAPublicKey r) {
            return "{\"kty\":\"RSA\"," + common + ",\"n\":\"" + b64(unsigned(r.getModulus())) + "\",\"e\":\""
                    + b64(unsigned(r.getPublicExponent())) + "\"}";
        }
        if (kp.getPublic() instanceof ECPublicKey e) {
            return "{\"kty\":\"EC\",\"crv\":\"P-256\"," + common + ",\"x\":\"" + b64(fixed(e.getW().getAffineX(), 32))
                    + "\",\"y\":\"" + b64(fixed(e.getW().getAffineY(), 32)) + "\"}";
        }
        byte[] spki = kp.getPublic().getEncoded();
        byte[] x = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
        return "{\"kty\":\"OKP\",\"crv\":\"Ed25519\"," + common + ",\"x\":\"" + b64(x) + "\"}";
    }

    public static String jwks(String... jwks) {
        return "{\"keys\":[" + String.join(",", jwks) + "]}";
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        return b[0] == 0 && b.length > 1 ? Arrays.copyOfRange(b, 1, b.length) : b;
    }

    private static byte[] fixed(BigInteger v, int len) {
        byte[] b = unsigned(v);
        if (b.length == len) {
            return b;
        }
        byte[] out = new byte[len];
        System.arraycopy(b, 0, out, len - b.length, b.length);
        return out;
    }
}
