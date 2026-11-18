package apigw.proxy;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/**
 * Pass-through view of a request body that stops at a byte limit (for bodies without a declared
 * length). Backpressure (pause/resume/fetch) is delegated unchanged.
 */
final class LimitedBody implements ReadStream<Buffer> {

    private final ReadStream<Buffer> source;
    private final long limit;
    private final Runnable onOverflow;
    private long seen;
    private boolean overflowed;

    LimitedBody(ReadStream<Buffer> source, long limit, Runnable onOverflow) {
        this.source = source;
        this.limit = limit;
        this.onOverflow = onOverflow;
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
            if (overflowed) {
                return;
            }
            seen += chunk.length();
            if (seen > limit) {
                overflowed = true;
                source.pause();
                onOverflow.run();
                return;
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
        if (!overflowed) {
            source.resume();
        }
        return this;
    }

    @Override
    public ReadStream<Buffer> fetch(long amount) {
        if (!overflowed) {
            source.fetch(amount);
        }
        return this;
    }

    @Override
    public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
        source.endHandler(endHandler);
        return this;
    }
}
