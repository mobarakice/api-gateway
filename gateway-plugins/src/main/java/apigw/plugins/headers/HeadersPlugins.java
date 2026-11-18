package apigw.plugins.headers;

import apigw.core.plugin.Exchange;
import apigw.core.plugin.Outcome;
import apigw.core.plugin.RequestPlugin;
import apigw.core.plugin.ResponseHeadersPlugin;
import io.vertx.core.MultiMap;

/** Compiled forms of the headers plugin; each implements only the phases it has work for. */
final class HeadersPlugins {

    private HeadersPlugins() {
    }

    /** Request edits only. */
    record RequestOnly(HeaderEdits request) implements RequestPlugin {
        @Override
        public Outcome onRequest(Exchange exchange) {
            request.apply(exchange.upstreamHeaders());
            return Outcome.CONTINUE;
        }
    }

    /** Response edits only. */
    record ResponseOnly(HeaderEdits response) implements ResponseHeadersPlugin {
        @Override
        public void onResponseHeaders(Exchange exchange, int status, MultiMap headers) {
            response.apply(headers);
        }
    }

    /** Both sides. */
    record Both(HeaderEdits request, HeaderEdits response) implements RequestPlugin, ResponseHeadersPlugin {
        @Override
        public Outcome onRequest(Exchange exchange) {
            request.apply(exchange.upstreamHeaders());
            return Outcome.CONTINUE;
        }

        @Override
        public void onResponseHeaders(Exchange exchange, int status, MultiMap headers) {
            response.apply(headers);
        }
    }
}
