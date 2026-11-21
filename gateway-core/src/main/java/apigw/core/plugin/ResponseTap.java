package apigw.core.plugin;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

/**
 * Observes the upstream response as it streams to the client (the response cache uses it to store a
 * copy). Called on the exchange's event loop; must not block or keep the buffers it is given beyond
 * copying them. After {@link #onEnd} or {@link #onAbort} nothing else is called.
 */
public interface ResponseTap {

    /**
     * The upstream status and headers, before header filters run. Copy what you need: the map is
     * edited afterwards.
     *
     * @return false to stop observing this response
     */
    boolean onHead(int status, MultiMap upstreamHeaders);

    /** One body chunk as it is written to the client. */
    void onChunk(Buffer chunk);

    /** The whole body reached the client. */
    void onEnd();

    /** The response did not complete (client gone, upstream reset, timeout). */
    void onAbort();
}
