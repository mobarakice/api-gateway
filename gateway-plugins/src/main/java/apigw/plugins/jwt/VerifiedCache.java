package apigw.plugins.jwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers tokens whose signature already verified with a given key, so the expensive public-key
 * check (ES256/EdDSA ~0.4 ms on the JDK) runs once per token rather than once per request. Only the
 * signature result is cached: every request still re-runs header, issuer, time, audience and
 * revocation checks.
 *
 * <p>Entries are keyed by the SHA-256 of the whole compact token (32 bytes, not the token itself)
 * and bound to the identity of the verifying key, so a rotated or removed key never vouches for a
 * token. Only successful verifications are inserted, so forged tokens cannot fill it. Bounded: at
 * capacity it is cleared (a later request just verifies again). One instance per compiled plugin,
 * shared by all event loops; a new snapshot starts empty.
 */
final class VerifiedCache {

    static final int CAPACITY = 50_000;

    private record Digest(byte[] value) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Digest d && Arrays.equals(value, d.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "Digest";
        }
    }

    private final Map<Digest, KeySet.Entry> entries = new ConcurrentHashMap<>();
    private final int capacity;

    VerifiedCache() {
        this(CAPACITY);
    }

    VerifiedCache(int capacity) {
        this.capacity = capacity;
    }

    boolean contains(String token, KeySet.Entry key) {
        return entries.get(digest(token)) == key;
    }

    void add(String token, KeySet.Entry key) {
        if (entries.size() >= capacity) {
            entries.clear();
        }
        entries.put(digest(token), key);
    }

    int size() {
        return entries.size();
    }

    private static Digest digest(String token) {
        try {
            return new Digest(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every Java platform", e);
        }
    }
}
