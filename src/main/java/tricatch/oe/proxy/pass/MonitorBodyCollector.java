package tricatch.oe.proxy.pass;

import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.HttpStream;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Accumulates a relayed body for the HTTP event monitor feed, capping memory use at
 * HTTP.MONITOR_BODY_LIMIT regardless of how the caller discovers the body's length (a known
 * Content-Length up front, or incrementally via chunked/until-close framing). Shared by
 * RelayContentLength, RelayChunked, and RelayUntilClose so the truncation policy — and the
 * message shown in place of a body that exceeded it — lives in exactly one place.
 */
class MonitorBodyCollector {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean exceeded = false;

    void add(byte[] data, int offset, int length) {
        if (exceeded) return;
        if (buffer.size() + length > HTTP.MONITOR_BODY_LIMIT) {
            exceeded = true;
            buffer.reset();
            return;
        }
        buffer.write(data, offset, length);
    }

    byte[] toEventBody(HttpStream.Flow flow) {
        if (!exceeded) return buffer.toByteArray();
        String prefix = flow == HttpStream.Flow.REQ ? "Request" : "Response";
        return (prefix + " body exceeds " + (HTTP.MONITOR_BODY_LIMIT / 1024 / 1024) + "MB and is not supported for display.")
                .getBytes(StandardCharsets.UTF_8);
    }
}
