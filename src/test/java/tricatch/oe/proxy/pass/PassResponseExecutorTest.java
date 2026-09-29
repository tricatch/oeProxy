package tricatch.oe.proxy.pass;

import org.junit.jupiter.api.Test;
import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.HttpStreamReader;
import tricatch.oe.proxy.http.io.HttpStreamWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PassResponseExecutorTest {

    @Test
    void http10ResponseWithNoConnectionHeader_stopsReusingTheConnection() throws Exception {
        // HTTP/1.0 defaults to close when no Connection header is present. If that default isn't
        // honored, the loop in run() treats the connection as keep-alive and misreads whatever
        // bytes follow as a second response on the same (already-finished) connection.
        byte[] resp1 = "HTTP/1.0 200 OK\r\nContent-Length: 5\r\n\r\nhello".getBytes(StandardCharsets.US_ASCII);
        byte[] resp2 = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nworld".getBytes(StandardCharsets.US_ASCII);
        byte[] combined = new byte[resp1.length + resp2.length];
        System.arraycopy(resp1, 0, combined, 0, resp1.length);
        System.arraycopy(resp2, 0, combined, resp1.length, resp2.length);

        HttpStreamReader serverIn = new HttpStreamReader(new ByteArrayInputStream(combined), HTTP.BODY_BUFFER_SIZE);
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        HttpStreamWriter clientOut = new HttpStreamWriter(rawOut);

        PassRequestExecutor passRequestExecutor = new PassRequestExecutor(null, 5000, 5000);
        PassResponseExecutor executor = new PassResponseExecutor(passRequestExecutor, serverIn, clientOut, 0L);

        executor.run();

        // resp1 was relayed; resp2 must never have been touched as a "next response".
        assertThat(rawOut.toString(StandardCharsets.US_ASCII)).contains("hello").doesNotContain("world");

        byte[] remaining = new byte[resp2.length];
        int n = serverIn.read(remaining);
        assertThat(n).isEqualTo(resp2.length);
        assertThat(remaining).isEqualTo(resp2);
    }

    @Test
    void staleGeneration_discardsItsResponseWithoutWritingToTheClient() throws Exception {
        // Simulates a PassResponseExecutor left over from a target that a subsequent request
        // already switched away from: it was spawned for generation 1, but by the time its
        // response is ready, PassRequestExecutor has moved on to generation 2 (a newer child took
        // over). It must discard its response instead of writing it to the shared clientOut.
        byte[] resp = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".getBytes(StandardCharsets.US_ASCII);
        HttpStreamReader serverIn = new HttpStreamReader(new ByteArrayInputStream(resp), HTTP.BODY_BUFFER_SIZE);
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        HttpStreamWriter clientOut = new HttpStreamWriter(rawOut);

        PassRequestExecutor passRequestExecutor = new PassRequestExecutor(null, 5000, 5000);
        bumpGeneration(passRequestExecutor); // generation is now 1
        PassResponseExecutor stale = new PassResponseExecutor(passRequestExecutor, serverIn, clientOut, 1L);
        bumpGeneration(passRequestExecutor); // a newer child has since taken over; now generation 2

        stale.run();

        assertThat(rawOut.toByteArray()).isEmpty();
    }

    private static void bumpGeneration(PassRequestExecutor executor) throws Exception {
        var field = PassRequestExecutor.class.getDeclaredField("socketGeneration");
        field.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) field.get(executor)).incrementAndGet();
    }
}
