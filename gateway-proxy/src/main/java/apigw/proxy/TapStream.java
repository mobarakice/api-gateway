package apigw.proxy;

import apigw.core.plugin.ResponseTap;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/** Passes an upstream body through unchanged while showing each chunk to a {@link ResponseTap}. */
final class TapStream implements ReadStream<Buffer> {

    private final ReadStream<Buffer> source;
    private final ResponseTap tap;
    private boolean done;

    TapStream(ReadStream<Buffer> source, ResponseTap tap) {
        this.source = source;
        this.tap = tap;
    }

    /** Ends observation without a complete body (idempotent). */
    void abort() {
        if (!done) {
            done = true;
            tap.onAbort();
        }
    }

    @Override
    public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        source.exceptionHandler(handler);
        return this;
    }

    @Override
    public ReadStream<Buffer> handler(Handler<Buffer> handler) {
        if (handler == null) {
            source.handler(null);
            return this;
        }
        source.handler(chunk -> {
            if (!done) {
                tap.onChunk(chunk);
            }
            handler.handle(chunk);
        });
        return this;
    }

    @Override
    public ReadStream<Buffer> pause() {
        source.pause();
        return this;
    }

    @Override
    public ReadStream<Buffer> resume() {
        source.resume();
        return this;
    }

    @Override
    public ReadStream<Buffer> fetch(long amount) {
        source.fetch(amount);
        return this;
    }

    @Override
    public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
        if (endHandler == null) {
            source.endHandler(null);
            return this;
        }
        // The whole upstream body has been read and handed on: the tap's copy is complete. This runs
        // before the client response ends (and so before the LOG phase).
        source.endHandler(v -> {
            completed();
            endHandler.handle(v);
        });
        return this;
    }

    /** The client got the whole body. */
    void completed() {
        if (!done) {
            done = true;
            tap.onEnd();
        }
    }
}
