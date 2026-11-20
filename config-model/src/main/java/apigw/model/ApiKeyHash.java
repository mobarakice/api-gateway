package apigw.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API key storage format {@code sha256$<salt>$<digest>}: SHA-256 over {@code salt || secret}, both
 * base64url without padding, salt 16 random bytes. Secrets are generated with at least 128 bits of
 * entropy ({@link #newSecret()}), so a fast salted hash is the right tool: a slow KDF would add tens
 * of milliseconds to every request without making a random 128-bit secret any harder to guess
 * (ADR 0015). JDK primitives only; comparison is constant-time.
 */
public final class ApiKeyHash {

    /** Minimum secret length accepted at request time (22 base64url chars = 132 bits). */
    public static final int MIN_SECRET_LENGTH = 22;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder D64 = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyHash() {
    }

    /** A parsed stored hash. */
    public record Parsed(byte[] salt, byte[] digest) {
    }

    /** Parses {@code sha256$salt$digest}; throws IllegalArgumentException when malformed. */
    public static Parsed parse(String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\$", -1);
        if (parts.length != 3 || !"sha256".equals(parts[0])) {
            throw new IllegalArgumentException("must be sha256$<salt>$<digest>");
        }
        byte[] salt = D64.decode(parts[1]);
        byte[] digest = D64.decode(parts[2]);
        if (salt.length < 16 || digest.length != 32) {
            throw new IllegalArgumentException("salt must be >= 16 bytes and digest 32 bytes");
        }
        return new Parsed(salt, digest);
    }

    public static String hash(String secret) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "sha256$" + B64.encodeToString(salt) + "$" + B64.encodeToString(digest(salt, secret));
    }

    /** A fresh random secret (32 bytes, base64url). */
    public static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return B64.encodeToString(b);
    }

    public static boolean matches(Parsed stored, String secret) {
        return MessageDigest.isEqual(stored.digest(), digest(stored.salt(), secret));
    }

    static byte[] digest(byte[] salt, String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(secret.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }
}
