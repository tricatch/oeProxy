package tricatch.oe.proxy.pass;

import org.junit.jupiter.api.Test;
import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.HttpStream;
import tricatch.oe.proxy.http.io.HttpStreamReader;
import tricatch.oe.proxy.http.io.HttpStreamWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RelayUntilCloseTest {

    @Test
    void relaysSmallBody_andReportsClose() throws Exception {
        byte[] body = "hello, close-delimited body".getBytes();
        HttpStreamReader in = new HttpStreamReader(new ByteArrayInputStream(body), HTTP.BODY_BUFFER_SIZE);
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        HttpStreamWriter out = new HttpStreamWriter(rawOut);

        HttpStream.Connection result = RelayUntilClose.relay("client1", "rid1", HttpStream.Flow.RES, in, out, false);

        assertThat(result).isEqualTo(HttpStream.Connection.CLOSE);
        assertThat(rawOut.toByteArray()).isEqualTo(body);
    }

    @Test
    void relaysFullBody_evenWhenItExceedsTheMonitorCap() throws Exception {
        // One byte over HTTP.MONITOR_BODY_LIMIT: the monitor-event collector must stop
        // accumulating past the cap, but every byte must still reach the client.
        byte[] body = new byte[HTTP.MONITOR_BODY_LIMIT + 1024];
        new Random(42).nextBytes(body);

        HttpStreamReader in = new HttpStreamReader(new ByteArrayInputStream(body), HTTP.BODY_BUFFER_SIZE);
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        HttpStreamWriter out = new HttpStreamWriter(rawOut);

        HttpStream.Connection result = RelayUntilClose.relay("client1", "rid2", HttpStream.Flow.RES, in, out, false);

        assertThat(result).isEqualTo(HttpStream.Connection.CLOSE);
        assertThat(rawOut.toByteArray()).isEqualTo(body);
    }
}
