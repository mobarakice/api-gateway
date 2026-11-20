package apigw.plugins.jwt;

import java.security.Key;
import java.util.List;
import java.util.Map;

/**
 * An immutable set of verification keys. {@link #find} never falls back from a {@code kid} miss to
 * another key; without a {@code kid} it accepts only an unambiguous match.
 */
public record KeySet(Map<String, Entry> byKid, List<Entry> all) {

    public static final KeySet EMPTY = new KeySet(Map.of(), List.of());

    public KeySet {
        byKid = Map.copyOf(byKid);
        all = List.copyOf(all);
    }

    /**
     * One key.
     *
     * @param alg the JWK's {@code alg}, if it declared one: then only that algorithm may use it
     */
    public record Entry(String kid, JwtAlgorithm.Family family, JwtAlgorithm alg, Key key, int curveBytes) {
    }

    /** The key for {@code kid} (or the single compatible key when there is no kid) usable with {@code alg}. */
    public Entry find(String kid, JwtAlgorithm alg) {
        if (kid != null) {
            Entry e = byKid.get(kid);
            return e != null && compatible(e, alg) ? e : null;
        }
        Entry found = null;
        for (Entry e : all) {
            if (compatible(e, alg)) {
                if (found != null) {
                    return null;
                }
                found = e;
            }
        }
        return found;
    }

    /** True when the set has a key with this kid at all (distinguishes "unknown kid" from "wrong key"). */
    public boolean hasKid(String kid) {
        return byKid.containsKey(kid);
    }

    static boolean compatible(Entry e, JwtAlgorithm alg) {
        if (e.family() != alg.family() || (e.alg() != null && e.alg() != alg)) {
            return false;
        }
        return alg.family() != JwtAlgorithm.Family.EC || e.curveBytes() == alg.curveBytes();
    }
}
