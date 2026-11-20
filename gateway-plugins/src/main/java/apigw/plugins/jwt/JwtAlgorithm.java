package apigw.plugins.jwt;

import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Optional;

/**
 * Supported JWS algorithms. {@code none} is deliberately absent. Each algorithm accepts exactly one key
 * family, which is what makes HS/RS confusion impossible: an RSA public key is never usable as an
 * HMAC secret, and an issuer's algorithm list is pinned in config.
 */
public enum JwtAlgorithm {
    RS256(Family.RSA, "SHA256withRSA", null, 0),
    RS384(Family.RSA, "SHA384withRSA", null, 0),
    RS512(Family.RSA, "SHA512withRSA", null, 0),
    PS256(Family.RSA, "RSASSA-PSS", pss("SHA-256", MGF1ParameterSpec.SHA256, 32), 0),
    PS384(Family.RSA, "RSASSA-PSS", pss("SHA-384", MGF1ParameterSpec.SHA384, 48), 0),
    PS512(Family.RSA, "RSASSA-PSS", pss("SHA-512", MGF1ParameterSpec.SHA512, 64), 0),
    ES256(Family.EC, "SHA256withECDSAinP1363Format", null, 64),
    ES384(Family.EC, "SHA384withECDSAinP1363Format", null, 96),
    ES512(Family.EC, "SHA512withECDSAinP1363Format", null, 132),
    EDDSA(Family.OKP, "Ed25519", null, 64),
    HS256(Family.HMAC, "HmacSHA256", null, 32),
    HS384(Family.HMAC, "HmacSHA384", null, 48),
    HS512(Family.HMAC, "HmacSHA512", null, 64);

    /** Key family an algorithm requires. */
    public enum Family { RSA, EC, OKP, HMAC }

    private final Family family;
    private final String jdkName;
    private final PSSParameterSpec pss;
    private final int size;

    JwtAlgorithm(Family family, String jdkName, PSSParameterSpec pss, int size) {
        this.family = family;
        this.jdkName = jdkName;
        this.pss = pss;
        this.size = size;
    }

    private static PSSParameterSpec pss(String digest, MGF1ParameterSpec mgf, int salt) {
        return new PSSParameterSpec(digest, "MGF1", mgf, salt, PSSParameterSpec.TRAILER_FIELD_BC);
    }

    public Family family() {
        return family;
    }

    String jdkName() {
        return jdkName;
    }

    PSSParameterSpec pss() {
        return pss;
    }

    /** Exact signature length for EC/EdDSA, MAC length (and minimum secret length) for HMAC; 0 = variable. */
    int size() {
        return size;
    }

    /** EC curve size in bytes of one coordinate (P-256 = 32, P-384 = 48, P-521 = 66), else 0. */
    int curveBytes() {
        return family == Family.EC ? size / 2 : 0;
    }

    /** The JOSE name ({@code EdDSA} keeps its mixed case). */
    public String joseName() {
        return this == EDDSA ? "EdDSA" : name();
    }

    /** Parses a JOSE {@code alg} value; empty for {@code none} and anything unsupported. */
    public static Optional<JwtAlgorithm> fromJose(String alg) {
        if (alg == null) {
            return Optional.empty();
        }
        for (JwtAlgorithm a : values()) {
            if (a.joseName().equals(alg)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }
}
