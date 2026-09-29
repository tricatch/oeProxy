package tricatch.oe.proxy.pass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.HttpStream;
import tricatch.oe.proxy.http.io.HttpStreamReader;
import tricatch.oe.proxy.http.io.HttpStreamWriter;
import tricatch.oe.proxy.event.HttpEvent;
import tricatch.oe.proxy.event.HttpEventManager;
import tricatch.oe.proxy.event.HttpEventType;

import java.io.IOException;

/**
 * Class for handling content-length based HTTP body relay operations
 */
public class RelayContentLength {

    private static final Logger logger = LoggerFactory.getLogger(RelayContentLength.class);

    public static HttpStream.Connection relay(String clientId, String rid, HttpStream.Flow flow, Long contentLength, HttpStreamReader in, HttpStreamWriter out, boolean monitored) throws IOException {
        if (contentLength == null || contentLength <= 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}, {}, No content length or zero content length", rid, flow);
            }
            return HttpStream.Connection.KEEP_ALIVE;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("{}, {}, Relaying content-length body: {} bytes", rid, flow, contentLength);
        }

        // monitored is decided once, at REQ_HEADER time, for this whole request (see
        // PassRequestExecutor) - not re-checked here - so a monitor tab that opens mid-relay
        // can't produce a REQ_BODY/RES_BODY event with no corresponding header event for the
        // monitor UI to attach it to.
        MonitorBodyCollector bodyCollector = monitored ? new MonitorBodyCollector() : null;

        byte[] buffer = new byte[HTTP.BODY_BUFFER_SIZE];
        // long: a body may be larger than 2 GiB (Content-Length is not limited to an int)
        long remainingBytes = contentLength;
        boolean truncated = false;

        while (remainingBytes > 0) {
            int bytesToRead = (int) Math.min(buffer.length, remainingBytes);
            int bytesRead = in.read(buffer, 0, bytesToRead);

            if (bytesRead == -1) {
                logger.warn("{}, {}, Unexpected end of stream while reading content-length body", rid, flow);
                truncated = true;
                break;
            }

            out.write(buffer, 0, bytesRead);
            out.flush();

            if (monitored) bodyCollector.add(buffer, 0, bytesRead);

            remainingBytes -= bytesRead;

            if (logger.isDebugEnabled()) {
                logger.debug("{}, {}, Relayed {} bytes of body, remaining: {}", rid, flow, bytesRead, remainingBytes);
            }
        }

        out.flush();

        if (monitored) {
            byte[] bodyForEvent = bodyCollector.toEventBody(flow);

            HttpEvent bodyEvent = new HttpEvent(clientId, rid, flow == HttpStream.Flow.REQ ? HttpEventType.REQ_BODY : HttpEventType.RES_BODY);
            bodyEvent.setBody(bodyForEvent);
            bodyEvent.setHttpStream(HttpStream.CONTENT_LENGTH);
            HttpEventManager.getInstance().enqueue(bodyEvent);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("{}, {}, Content-length body relay completed", rid, flow);
        }

        // A body that ended early (EOF before contentLength was fully read) leaves the connection
        // desynced, so it must not be handed back for reuse.
        return truncated ? HttpStream.Connection.CLOSE : HttpStream.Connection.KEEP_ALIVE;
    }
}
