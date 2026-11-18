package apigw.config.etcd;

import java.util.List;

/**
 * One message on a watch stream.
 *
 * @param revision        store revision of this message
 * @param created         the watch was just created
 * @param canceled        etcd canceled the watch (see {@code compactRevision} / {@code cancelReason})
 * @param compactRevision non-zero when the requested start revision was compacted: resync required
 * @param cancelReason    etcd's reason text when canceled
 * @param events          changes, in revision order
 */
public record WatchResponse(long revision, boolean created, boolean canceled, long compactRevision,
        String cancelReason, List<WatchEvent> events) {

    public WatchResponse {
        events = List.copyOf(events);
    }

    /** A progress notification: no events, not a create or cancel, only the current revision. */
    public boolean progressNotify() {
        return events.isEmpty() && !created && !canceled;
    }
}
