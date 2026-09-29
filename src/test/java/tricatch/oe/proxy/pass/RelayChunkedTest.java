package tricatch.oe.proxy.pass;

import org.junit.jupiter.api.Test;
import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.HttpStream;
import tricatch.oe.proxy.http.io.HttpStreamReader;
import tricatch.oe.proxy.http.io.HttpStreamWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RelayChunkedTest {

    private byte[] chunkedEncode(byte[] data, int chunkSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        while (offset < data.length) {
            int len = Math.min(chunkSize, data.length - offset);
            out.write(Integer.toHexString(len).getBytes(StandardCharsets.US_ASCII));
            out.write(HTTP.CRLF);
            out.write(data, offset, len);
            out.write(HTTP.CRLF);
            offset += len;
        }
        out.write('0');
        out.write(HTTP.CRLF);
        out.write(HTTP.CRLF); // empty trailer section
        return out.toByteArray();
    }

    @Test
    void relaysSmallChunkedBody_byteForByte_andReportsKeepAlive() throws Exception {
        byte[] body = "hello, chunked body".getBytes();
        byte[] encoded = chunkedEncode(body, 6);

        HttpStreamReader in = new HttpStreamReader(new ByteArrayInputStream(encoded), HTTP.BODY_BUFFER_SIZE);
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        HttpStreamWriter out = new HttpStreamWriter(rawOut);

        HttpStream.Connection result = RelayChunked.relay("client1", "rid1", HttpStream.Flow.RES, in, out, false);

        assertThat(result).isEqualTo(HttpStream.Connection.KEEP_ALIVE);
        assertThat(rawOut.toByteArray()).isEqualTo(encoded);
    }

    @Test
    void relaysFullChunkedBody_evenWhenItExceedsTheMonitorCap() throws Exception {
        // One chunk's worth over HTTP.MONITOR_BODY_LIMIT: the monitor-event collector must
        // stop accumulating past the cap, but the full wire-format stream must still reach the client.
        byte[] body = new byte[HTTP.MONITOR_BODY_LIMIT + 1024];
        new Random(7).nextBytes(body);
        byte[] encoded = chunkedEncode(body, HTTP.BODY_BUFFER_SIZE);

        HttpStreamReader in = new HttpStreamReader(new ByteArrayInputStream(encoded), HTTP.BODY_BUFFER_SIZE);
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        HttpStreamWriter out = new HttpStreamWriter(rawOut);

        HttpStream.Connection result = RelayChunked.relay("client1", "rid2", HttpStream.Flow.RES, in, out, false);

        assertThat(result).isEqualTo(HttpStream.Connection.KEEP_ALIVE);
        assertThat(rawOut.toByteArray()).isEqualTo(encoded);
    }

    @Test
    void oversizedChunkSize_isRejectedWithoutMisreadingSubsequentBytes() throws Exception {
        // "80000000" overflows a signed 32-bit int (sets the sign bit). Before the fix,
        // parseHexChunkSize returned a negative chunkSize, the "remainingBytes > 0" loop was
        // skipped entirely, and the next 2 bytes of whatever followed on the wire were misread
        // as the chunk's terminating CRLF. The fix must reject the chunk-size line up front
        // (NumberFormatException, caught by the existing handler) and touch none of the bytes
        // that follow it.
        byte[] trailingBytes = "REST-OF-STREAM-MUST-NOT-BE-TOUCHED".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.write("80000000".getBytes(StandardCharsets.US_ASCII));
        input.write(HTTP.CRLF);
        input.write(trailingBytes);

        HttpStreamReader in = new HttpStreamReader(new ByteArrayInputStream(input.toByteArray()), HTTP.BODY_BUFFER_SIZE);
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        HttpStreamWriter out = new HttpStreamWriter(rawOut);

        HttpStream.Connection result = RelayChunked.relay("client1", "rid3", HttpStream.Flow.RES, in, out, false);

        // A chunk-size parse failure leaves the connection desynced: it must never be reused.
        assertThat(result).isEqualTo(HttpStream.Connection.CLOSE);

        // Nothing should have been written to the client for a chunk-size line that was
        // rejected before any framing decision was made.
        assertThat(rawOut.toByteArray()).isEmpty();

        // The reader must still be positioned exactly at the start of the trailing bytes,
        // proving relay() never attempted to consume any of them as chunk data/terminator.
        byte[] remaining = new byte[trailingBytes.length];
        int n = in.read(remaining);
        assertThat(n).isEqualTo(trailingBytes.length);
        assertThat(remaining).isEqualTo(trailingBytes);
    }

    @Test
    void prematureEofMidChunkData_reportsClose() throws Exception {
        // The backend declares a 20-byte chunk but the connection dies after only 5 bytes.
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.write("14".getBytes(StandardCharsets.US_ASCII)); // 0x14 = 20
        input.write(HTTP.CRLF);
        input.write("hello".getBytes(StandardCharsets.US_ASCII)); // only 5 of the promised 20 bytes

        HttpStreamReader in = new HttpStreamReader(new ByteArrayInputStream(input.toByteArray()), HTTP.BODY_BUFFER_SIZE);
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        HttpStreamWriter out = new HttpStreamWriter(rawOut);

        HttpStream.Connection result = RelayChunked.relay("client1", "rid4", HttpStream.Flow.RES, in, out, false);

        assertThat(result).isEqualTo(HttpStream.Connection.CLOSE);
    }
}
