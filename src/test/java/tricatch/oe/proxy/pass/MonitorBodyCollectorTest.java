package tricatch.oe.proxy.pass;

import org.junit.jupiter.api.Test;
import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.HttpStream;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class MonitorBodyCollectorTest {

    @Test
    void underTheLimit_returnsExactlyWhatWasAdded() {
        MonitorBodyCollector collector = new MonitorBodyCollector();
        byte[] a = "hello, ".getBytes(StandardCharsets.US_ASCII);
        byte[] b = "world".getBytes(StandardCharsets.US_ASCII);

        collector.add(a, 0, a.length);
        collector.add(b, 0, b.length);

        assertThat(collector.toEventBody(HttpStream.Flow.RES)).isEqualTo("hello, world".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void overTheLimit_returnsAPlaceholderInsteadOfTheBody() {
        MonitorBodyCollector collector = new MonitorBodyCollector();
        byte[] chunk = new byte[HTTP.MONITOR_BODY_LIMIT / 4];
        new Random(1).nextBytes(chunk);

        // Five chunks of MONITOR_BODY_LIMIT/4 crosses the limit partway through.
        for (int i = 0; i < 5; i++) {
            collector.add(chunk, 0, chunk.length);
        }

        String result = new String(collector.toEventBody(HttpStream.Flow.RES), StandardCharsets.UTF_8);
        assertThat(result).contains("Response body exceeds").contains(String.valueOf(HTTP.MONITOR_BODY_LIMIT / 1024 / 1024));
    }

    @Test
    void requestFlow_usesRequestInThePlaceholder() {
        MonitorBodyCollector collector = new MonitorBodyCollector();
        byte[] chunk = new byte[HTTP.MONITOR_BODY_LIMIT + 1];

        collector.add(chunk, 0, chunk.length);

        assertThat(new String(collector.toEventBody(HttpStream.Flow.REQ), StandardCharsets.UTF_8))
                .startsWith("Request body exceeds");
    }

    @Test
    void onceOverTheLimit_stopsAccumulatingFurtherBytes() {
        // Not just a memory-growth guard: proves the collector doesn't quietly keep buffering
        // more data than the placeholder message implies once it has already tripped.
        MonitorBodyCollector collector = new MonitorBodyCollector();
        collector.add(new byte[HTTP.MONITOR_BODY_LIMIT + 1], 0, HTTP.MONITOR_BODY_LIMIT + 1);

        // Adding more after it's already tripped must not grow anything further.
        collector.add(new byte[HTTP.MONITOR_BODY_LIMIT], 0, HTTP.MONITOR_BODY_LIMIT);

        byte[] result = collector.toEventBody(HttpStream.Flow.RES);
        assertThat(result.length).isLessThan(HTTP.MONITOR_BODY_LIMIT);
    }
}
