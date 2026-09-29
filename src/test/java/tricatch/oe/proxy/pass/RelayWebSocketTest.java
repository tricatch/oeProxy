package tricatch.oe.proxy.pass;

import org.junit.jupiter.api.Test;
import tricatch.oe.proxy.http.io.HttpStreamReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RelayWebSocketTest {

    /** Hands out at most {@code chunk} bytes per read, like a socket whose segments end mid-frame. */
    private static InputStream trickle(byte[] data, int chunk) {
        var source = new ByteArrayInputStream(data);
        return new InputStream() {
            @Override public int read() { return source.read(); }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                return source.read(b, off, Math.min(len, chunk));
            }
        };
    }

    private static byte[] maskedFrame(int payloadLength) {
        // FIN + text, masked, 16-bit extended length, 4-byte masking key, payload
        var frame = new byte[2 + 2 + 4 + payloadLength];
        frame[0] = (byte) 0x81;
        frame[1] = (byte) (0x80 | 126);
        frame[2] = (byte) (payloadLength >> 8);
        frame[3] = (byte) payloadLength;
        frame[4] = 1; frame[5] = 2; frame[6] = 3; frame[7] = 4;
        for (int i = 0; i < payloadLength; i++) frame[8 + i] = (byte) i;
        return frame;
    }

    @Test
    void readFrame_headerSplitAcrossReads_isReadWhole() throws IOException {
        var data = maskedFrame(300);
        // One byte per read: the extended length and masking key each arrive in pieces.
        var frame = RelayWebSocket.readFrame(new HttpStreamReader(trickle(data, 1), 16));

        assertThat(frame).isNotNull();
        assertThat(frame.getOpcode()).isEqualTo(1);
        assertThat(frame.getPayload()).hasSize(300);
        assertThat(frame.getMaskingKey()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void readFrame_sixtyFourBitLengthSplitAcrossReads_isReadWhole() throws IOException {
        var payload = new byte[70000];
        var data = new byte[2 + 8 + payload.length];
        data[0] = (byte) 0x82;
        data[1] = 127;
        data[2 + 7] = (byte) (payload.length & 0xFF);
        data[2 + 6] = (byte) ((payload.length >> 8) & 0xFF);
        data[2 + 5] = (byte) ((payload.length >> 16) & 0xFF);

        var frame = RelayWebSocket.readFrame(new HttpStreamReader(trickle(data, 3), 16));

        assertThat(frame).isNotNull();
        assertThat(frame.getPayload()).hasSize(70000);
    }

    @Test
    void readFrame_streamEndingInsideTheHeader_isEndOfStream() throws IOException {
        var data = maskedFrame(10);
        var truncated = java.util.Arrays.copyOf(data, 5); // stops inside the masking key

        assertThat(RelayWebSocket.readFrame(new HttpStreamReader(trickle(truncated, 1), 16))).isNull();
    }
}
