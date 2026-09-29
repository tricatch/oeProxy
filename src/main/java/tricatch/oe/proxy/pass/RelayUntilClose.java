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
 * Class for handling until-close HTTP body relay operations
 */
public class RelayUntilClose {
    
    private static final Logger logger = LoggerFactory.getLogger(RelayUntilClose.class);
    
    /**
     * Relay response body until connection close
     * @param clientId Client identifier
     * @param rid Request ID for logging
     * @param flow Body stream flow direction (REQ/RES)
     * @param in Input stream reader
     * @param out Output stream writer
     * @return HttpStream.Connection indicating whether connection should be closed
     * @throws IOException when I/O error occurs
     */
    public static HttpStream.Connection relay(String clientId, String rid, HttpStream.Flow flow, HttpStreamReader in, HttpStreamWriter out, boolean monitored) throws IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("{}, {}, Relaying until-close body"
                    , rid
                    , flow
            );
        }

        byte[] buffer = new byte[HTTP.BODY_BUFFER_SIZE];
        int totalBytesRelayed = 0;
        // monitored is decided once, at REQ_HEADER time, for this whole request (see
        // PassRequestExecutor) - not re-checked here.
        MonitorBodyCollector bodyCollector = monitored ? new MonitorBodyCollector() : null;

        while (true) {
            int bytesRead = in.read(buffer);

            if (bytesRead == -1) {
                // End of stream
                break;
            }

            out.write(buffer, 0, bytesRead);
            totalBytesRelayed += bytesRead;

            if (monitored) bodyCollector.add(buffer, 0, bytesRead);

            if (logger.isDebugEnabled()) {
                logger.debug("{}, {}, Relayed {} bytes of body, total: {}"
                        , rid
                        , flow
                        , bytesRead
                        , totalBytesRelayed
                );
            }
        }

        out.flush();

        if (monitored) {
            byte[] bodyForEvent = bodyCollector.toEventBody(flow);

            // Enqueue body HttpEvent
            HttpEvent bodyEvent = new HttpEvent(clientId, rid,
                flow == HttpStream.Flow.REQ ? HttpEventType.REQ_BODY : HttpEventType.RES_BODY);
            bodyEvent.setBody(bodyForEvent);
            bodyEvent.setHttpStream(HttpStream.UNTIL_CLOSE);
            HttpEventManager.getInstance().enqueue(bodyEvent);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("{}, {}, Until-close body relay completed, total bytes: {}"
                    , rid
                    , flow
                    , totalBytesRelayed
            );
        }
        
        // Until-close means connection should be closed
        return HttpStream.Connection.CLOSE;
    }
}
