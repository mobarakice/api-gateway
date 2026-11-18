package apigw.core.snapshot;

import apigw.model.Timeouts;

/** Fully resolved timeouts in milliseconds; {@code totalMs == 0} means no total limit. */
public record ResolvedTimeouts(int connectMs, int firstByteMs, int idleMs, int totalMs) {

    static ResolvedTimeouts of(Timeouts t) {
        return new ResolvedTimeouts(t.connectMs(), t.firstByteMs(), t.idleMs(), t.totalMs());
    }
}
