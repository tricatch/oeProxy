package tricatch.oe.proxy.pass;

import org.junit.jupiter.api.Test;
import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.HttpStream;
import tricatch.oe.proxy.http.io.HttpStreamReader;
import tricatch.oe.proxy.http.io.HttpStreamWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RelayContentLengthTest {

    @Test
    void relaysFullBody_andReportsKeepAlive() throws Exception {
        byte[] body = "the full, complete body".getBytes();

        HttpStreamReader in = new HttpStreamReader(new ByteArrayInputStream(body), HTTP.BODY_BUFFER_SIZE);
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        HttpStreamWriter out = new HttpStreamWriter(rawOut);

        HttpStream.Connection result = RelayContentLength.relay("client1", "rid1", HttpStream.Flow.RES, (long) body.length, in, out, false);

        assertThat(result).isEqualTo(HttpStream.Connection.KEEP_ALIVE);
        assertThat(rawOut.toByteArray()).isEqualTo(body);
    }

    @Test
    void prematureEof_reportsClose_insteadOfKeepAlive() throws Exception {
        // Content-Length declares 10000 bytes but the backend only sends 4000 before closing.
        byte[] actualBytes = new byte[4000];
        for (int i = 0; i < actualBytes.length; i++) actualBytes[i] = (byte) (i % 256);

        HttpStreamReader in = new HttpStreamReader(new ByteArrayInputStream(actualBytes), HTTP.BODY_BUFFER_SIZE);
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        HttpStreamWriter out = new HttpStreamWriter(rawOut);

        HttpStream.Connection result = RelayContentLength.relay("client1", "rid2", HttpStream.Flow.RES, 10000L, in, out, false);

        // A truncated body means the connection is dead/desynced and must not be reused.
        assertThat(result).isEqualTo(HttpStream.Connection.CLOSE);
        assertThat(rawOut.toByteArray()).isEqualTo(actualBytes);
    }

    /** An endless-enough source: {@code total} zero bytes, produced without holding them in memory. */
    private static final class ZeroBytes extends java.io.InputStream {
        private long remaining;
        ZeroBytes(long total) { this.remaining = total; }
        @Override public int read() { return remaining-- > 0 ? 0 : -1; }
        @Override public int read(byte[] b, int off, int len) {
            if (remaining <= 0) return -1;
            int n = (int) Math.min(len, remaining);
            remaining -= n;
            return n;
        }
    }

    /** Counts what is written to it instead of keeping it. */
    private static final class CountingSink extends java.io.OutputStream {
        long count;
        @Override public void write(int b) { count++; }
        @Override public void write(byte[] b, int off, int len) { count += len; }
    }

    @Test
    void relaysABodyLargerThanIntMax_completely() throws Exception {
        // Content-Length is not limited to an int: a download or upload over 2 GiB must be relayed in
        // full (the byte counter used to be an int and could not even represent this length).
        long length = Integer.MAX_VALUE + 5000L;
        HttpStreamReader in = new HttpStreamReader(new ZeroBytes(length), HTTP.BODY_BUFFER_SIZE);
        CountingSink sink = new CountingSink();
        HttpStreamWriter out = new HttpStreamWriter(sink);

        HttpStream.Connection result = RelayContentLength.relay("client1", "rid3", HttpStream.Flow.REQ, length, in, out, false);

        assertThat(result).isEqualTo(HttpStream.Connection.KEEP_ALIVE);
        assertThat(sink.count).isEqualTo(length);
    }
}
