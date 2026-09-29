package tricatch.oe.proxy.http.io;

import tricatch.oe.proxy.http.HTTP;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Buffered writer over a socket OutputStream.
 *
 * Deliberately does NOT extend java.io.BufferedOutputStream: its write()/flush()
 * methods are synchronized, and a virtual thread blocked inside a synchronized
 * method while the socket write blocks (e.g. a slow client) pins its carrier
 * thread instead of yielding it. See HttpStreamReader for the matching read-side
 * rationale. Each instance here is only ever driven by a single thread at a time,
 * so no synchronization is needed to begin with.
 */
public class HttpStreamWriter implements Closeable {

    private static final int BUFFER_SIZE = 8192;

    private final OutputStream out;
    private final byte[] buf;
    private int count = 0;

    public HttpStreamWriter(OutputStream out) {
        this.out = out;
        this.buf = new byte[BUFFER_SIZE];
    }

    private void flushBuffer() throws IOException {
        if (count > 0) {
            out.write(buf, 0, count);
            count = 0;
        }
    }

    public void write(int b) throws IOException {
        if (count >= buf.length) flushBuffer();
        buf[count++] = (byte) b;
    }

    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        if (len >= buf.length) {
            flushBuffer();
            out.write(b, off, len);
            return;
        }
        if (len > buf.length - count) flushBuffer();
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }

    public void flush() throws IOException {
        flushBuffer();
        out.flush();
    }

    public void close() throws IOException {
        flush();
        out.close();
    }

    public void writeHeaders(HeaderLines headerLines) throws IOException {
        if (headerLines == null) {
            throw new NullPointerException("HeaderLines cannot be null");
        }

        // Write all header lines
        for (ByteBuffer headerBuffer : headerLines) {
            write(headerBuffer.getBuffer(), 0, headerBuffer.getLength());
            write(HTTP.CRLF);
        }

        // Write empty line to end headers
        write(HTTP.CRLF);
        flush();
    }

}
