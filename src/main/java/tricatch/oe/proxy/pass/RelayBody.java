package tricatch.oe.proxy.pass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tricatch.oe.proxy.http.io.HttpStream;
import tricatch.oe.proxy.http.io.HttpRequest;
import tricatch.oe.proxy.http.io.HttpResponse;
import tricatch.oe.proxy.http.io.HttpStreamReader;
import tricatch.oe.proxy.http.io.HttpStreamWriter;

import java.io.IOException;

public class RelayBody {

    private static final Logger logger = LoggerFactory.getLogger(RelayBody.class);

    public static HttpStream.Connection relayResponseBody(String clientId, String rid, HttpStream.Flow flow, HttpResponse response, HttpStreamReader in, HttpStreamWriter out, boolean monitored) throws IOException {
        HttpStream httpStream = response.getBodyStream();

        if (logger.isDebugEnabled()) {
            logger.debug("{}, {}, Relaying body with type: {}", rid, flow, httpStream);
        }

        switch (httpStream) {
            case NONE:
            case NULL:
                if (logger.isDebugEnabled()) {
                    logger.trace("{}, {}, No body to relay", rid, flow);
                }
                return HttpStream.Connection.KEEP_ALIVE;

            case CONTENT_LENGTH:
                return RelayContentLength.relay(clientId, rid, flow, response.getContentLength(), in, out, monitored);

            case CHUNKED:
                return RelayChunked.relay(clientId, rid, flow, in, out, monitored);

            case WEBSOCKET:
                return RelayWebSocket.relay(clientId, rid, flow, in, out, monitored);

            case UNTIL_CLOSE:
                return RelayUntilClose.relay(clientId, rid, flow, in, out, monitored);

            default:
                logger.warn("{}, {}, Unknown body stream type: {}", rid, flow, httpStream);
                return HttpStream.Connection.KEEP_ALIVE;
        }
    }

    public static HttpStream.Connection relayRequestBody(String clientId, String rid, HttpStream.Flow flow, HttpRequest request, HttpStreamReader in, HttpStreamWriter out, boolean monitored) throws IOException {
        HttpStream httpStream = request.getHttpStream();

        if (logger.isDebugEnabled()) {
            logger.debug("{}, {}, Relaying body with type: {}", rid, flow, httpStream);
        }

        switch (httpStream) {
            case NONE:
            case NULL:
                if (logger.isDebugEnabled()) {
                    logger.trace("{}, {}, No body to relay", rid, flow);
                }
                return HttpStream.Connection.KEEP_ALIVE;

            case CONTENT_LENGTH:
                return RelayContentLength.relay(clientId, rid, flow, request.getContentLength(), in, out, monitored);

            case CHUNKED:
                return RelayChunked.relay(clientId, rid, flow, in, out, monitored);

            case WEBSOCKET:
                return RelayWebSocket.relay(clientId, rid, flow, in, out, monitored);

            case UNTIL_CLOSE:
                return RelayUntilClose.relay(clientId, rid, flow, in, out, monitored);

            default:
                logger.warn("{}, {}, Unknown body stream type: {}", rid, flow, httpStream);
                return HttpStream.Connection.KEEP_ALIVE;
        }
    }
}
