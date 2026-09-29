package tricatch.oe.proxy.standalone;

import org.junit.jupiter.api.Test;
import tricatch.oe.proxy.event.HttpEvent;
import tricatch.oe.proxy.event.HttpEventType;
import tricatch.oe.proxy.http.io.ByteBuffer;
import tricatch.oe.proxy.http.io.HeaderLines;
import tricatch.oe.proxy.http.io.HttpStream;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleMonitorConsumerTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream stream = new PrintStream(captured, true, StandardCharsets.UTF_8);
    private final ConsoleMonitorConsumer consumer = consumer(MonitorLevel.BASIC);

    private ConsoleMonitorConsumer consumer(MonitorLevel level) {
        return new ConsoleMonitorConsumer("0", "console", stream, level);
    }

    private static HeaderLines headers(String... lines) {
        HeaderLines h = new HeaderLines(lines.length);
        for (String line : lines) h.addHeaderLine(new ByteBuffer(line.getBytes(StandardCharsets.UTF_8)));
        return h;
    }

    private static HttpEvent event(HttpEventType type, String rid, long timestamp, HeaderLines headers) {
        HttpEvent e = new HttpEvent("0", rid, type);
        e.setTimestamp(timestamp);
        e.setHeaders(headers);
        return e;
    }

    private static HttpEvent withStream(HttpEvent e, HttpStream s) {
        e.setHttpStream(s);
        return e;
    }

    private static HttpEvent body(HttpEventType type, String rid, byte[] bytes) {
        HttpEvent e = new HttpEvent("0", rid, type);
        e.setBody(bytes);
        return e;
    }

    private HttpEvent req(String rid, long ts) {
        return event(HttpEventType.REQ_HEADER, rid, ts, headers("GET /api/items?x=1 HTTP/1.1", "Host: app.test", "Accept: */*"));
    }

    private HttpEvent res(String rid, long ts) {
        return event(HttpEventType.RES_HEADER, rid, ts, headers("HTTP/1.1 200 OK", "Content-Length: 0"));
    }

    private HttpEvent post(String rid, long ts, HttpStream s) {
        return withStream(event(HttpEventType.REQ_HEADER, rid, ts,
                headers("POST /submit HTTP/1.1", "Host: app.test", "Content-Type: application/json", "Content-Length: 7")), s);
    }

    private HttpEvent resWith(String rid, long ts, HttpStream s, String... lines) {
        String[] all = new String[lines.length + 1];
        all[0] = "HTTP/1.1 200 OK";
        System.arraycopy(lines, 0, all, 1, lines.length);
        return withStream(event(HttpEventType.RES_HEADER, rid, ts, headers(all)), s);
    }

    private String[] lines() {
        String out = captured.toString(StandardCharsets.UTF_8);
        return out.isEmpty() ? new String[0] : out.strip().split("\\R");
    }

    private static byte[] gzip(String s) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }

    // ---- BASIC ----

    @Test
    void requestThenResponse_printsOneLine() {
        consumer.process(req("r1", 1_000_000L));
        assertThat(lines()).isEmpty();
        consumer.process(res("r1", 1_000_042L));

        assertThat(lines()).hasSize(1);
        assertThat(lines()[0])
                .matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}  GET app\\.test/api/items\\?x=1 -> 200  42ms");
    }

    @Test
    void responseThenRequest_printsExactlyOneLine() {
        consumer.process(res("r2", 2_000_015L));
        assertThat(lines()).isEmpty();
        consumer.process(req("r2", 2_000_000L));

        assertThat(lines()).hasSize(1);
        assertThat(lines()[0]).contains("GET app.test/api/items?x=1 -> 200  15ms");
    }

    @Test
    void bodyAndWebSocketEvents_printNothing() {
        consumer.process(event(HttpEventType.REQ_BODY, "r3", 1L, null));
        consumer.process(event(HttpEventType.RES_BODY, "r3", 2L, null));
        consumer.process(event(HttpEventType.WS_FRAME, "r3", 3L, null));

        assertThat(lines()).isEmpty();
    }

    @Test
    void basic_bodyEventsDoNotBlockOrLeak() {
        consumer.process(req("r3b", 1L));
        consumer.process(body(HttpEventType.REQ_BODY, "r3b", "secret".getBytes(StandardCharsets.UTF_8)));
        consumer.process(res("r3b", 2L));

        assertThat(lines()).hasSize(1);
        assertThat(captured.toString(StandardCharsets.UTF_8)).doesNotContain("secret");
    }

    @Test
    void unmatchedRequest_doesNotPrint() {
        consumer.process(req("r4", 1L));

        assertThat(lines()).isEmpty();
    }

    @Test
    void unparsableHeaders_printDashesWithoutThrowing() {
        consumer.process(event(HttpEventType.REQ_HEADER, "r5", 10L, null));
        consumer.process(event(HttpEventType.RES_HEADER, "r5", 20L, headers("garbage")));

        assertThat(lines()).hasSize(1);
        assertThat(lines()[0]).contains("- -- -> -  10ms");
    }

    @Test
    void consuming_doesNotMutateHeaderBuffers() {
        HeaderLines requestHeaders = headers("GET /a HTTP/1.1", "Host: h.test");
        HeaderLines responseHeaders = headers("HTTP/1.1 404 Not Found");
        int[] before = {requestHeaders.get(0).getLength(), requestHeaders.get(1).getLength(), responseHeaders.get(0).getLength()};
        String reqBefore = requestHeaders.toString();

        consumer.process(event(HttpEventType.REQ_HEADER, "r6", 1L, requestHeaders));
        consumer.process(event(HttpEventType.RES_HEADER, "r6", 2L, responseHeaders));

        assertThat(lines()[0]).contains("GET h.test/a -> 404  1ms");
        assertThat(requestHeaders).hasSize(2);
        assertThat(responseHeaders).hasSize(1);
        assertThat(new int[]{requestHeaders.get(0).getLength(), requestHeaders.get(1).getLength(), responseHeaders.get(0).getLength()})
                .containsExactly(before);
        assertThat(requestHeaders.toString()).isEqualTo(reqBefore);
    }

    // ---- HEADERS ----

    @Test
    void headers_printsSummaryThenHeaderLines() {
        ConsoleMonitorConsumer c = consumer(MonitorLevel.HEADERS);
        c.process(req("h1", 1_000_000L));
        c.process(res("h1", 1_000_042L));

        String[] out = lines();
        assertThat(out).hasSize(1 + 3 + 2);
        assertThat(out[0]).contains("GET app.test/api/items?x=1 -> 200  42ms");
        assertThat(out[1]).isEqualTo("  > GET /api/items?x=1 HTTP/1.1");
        assertThat(out[2]).isEqualTo("  > Host: app.test");
        assertThat(out[3]).isEqualTo("  > Accept: */*");
        assertThat(out[4]).isEqualTo("  < HTTP/1.1 200 OK");
        assertThat(out[5]).isEqualTo("  < Content-Length: 0");
    }

    @Test
    void headers_ignoresBodyAndWsEvents() {
        ConsoleMonitorConsumer c = consumer(MonitorLevel.HEADERS);
        c.process(req("h2", 1L));
        c.process(body(HttpEventType.REQ_BODY, "h2", "abc".getBytes(StandardCharsets.UTF_8)));
        c.process(res("h2", 2L));
        c.process(event(HttpEventType.WS_FRAME, "h2", 3L, null));

        assertThat(lines()).hasSize(1 + 3 + 2);
        assertThat(captured.toString(StandardCharsets.UTF_8)).doesNotContain("[body");
    }

    // ---- FULL ----

    @Test
    void full_waitsForBothBodies() {
        ConsoleMonitorConsumer c = consumer(MonitorLevel.FULL);
        c.process(post("f1", 1_000L, HttpStream.CONTENT_LENGTH));
        c.process(resWith("f1", 1_010L, HttpStream.CHUNKED, "Content-Type: text/plain"));
        assertThat(lines()).isEmpty();

        c.process(body(HttpEventType.REQ_BODY, "f1", "{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        assertThat(lines()).isEmpty();

        c.process(body(HttpEventType.RES_BODY, "f1", "hello\nworld".getBytes(StandardCharsets.UTF_8)));

        String[] out = lines();
        assertThat(out[0]).contains("POST app.test/submit -> 200  10ms");
        assertThat(out).contains(
                "  > [body 7 bytes]", "  > {\"a\":1}",
                "  < [body 11 bytes]", "  < hello", "  < world");
        // Request body block comes after the request headers and before the response headers.
        String all = String.join("\n", out);
        assertThat(all.indexOf("  > [body")).isGreaterThan(all.indexOf("  > Content-Length: 7"));
        assertThat(all.indexOf("  > [body")).isLessThan(all.indexOf("  < HTTP/1.1 200 OK"));
        assertThat(all.indexOf("  < [body")).isGreaterThan(all.indexOf("  < Content-Type: text/plain"));
    }

    @Test
    void full_doesNotWaitWhenStreamIsNone() {
        ConsoleMonitorConsumer c = consumer(MonitorLevel.FULL);
        c.process(withStream(req("f2", 1L), HttpStream.NONE));
        c.process(resWith("f2", 2L, HttpStream.NULL));

        assertThat(lines()[0]).contains("GET app.test/api/items?x=1 -> 200  1ms");
        assertThat(captured.toString(StandardCharsets.UTF_8)).doesNotContain("[body");
    }

    @Test
    void full_doesNotWaitForHeadResponseOrNoContentStatuses() {
        ConsoleMonitorConsumer c = consumer(MonitorLevel.FULL);
        c.process(withStream(event(HttpEventType.REQ_HEADER, "f3", 1L, headers("HEAD /x HTTP/1.1", "Host: a")), HttpStream.NONE));
        c.process(resWith("f3", 2L, HttpStream.CONTENT_LENGTH, "Content-Length: 100"));
        assertThat(lines()).hasSize(1 + 2 + 2);

        captured.reset();
        c.process(withStream(req("f4", 1L), HttpStream.NONE));
        c.process(withStream(event(HttpEventType.RES_HEADER, "f4", 2L, headers("HTTP/1.1 304 Not Modified")), HttpStream.CONTENT_LENGTH));
        assertThat(lines()[0]).contains("-> 304");

        captured.reset();
        c.process(withStream(req("f5", 1L), HttpStream.NONE));
        c.process(withStream(event(HttpEventType.RES_HEADER, "f5", 2L, headers("HTTP/1.1 100 Continue")), null));
        assertThat(lines()[0]).contains("-> 100");
    }

    @Test
    void full_outOfOrderArrival_printsOnceWhenComplete() {
        ConsoleMonitorConsumer c = consumer(MonitorLevel.FULL);
        c.process(body(HttpEventType.RES_BODY, "f6", "pong".getBytes(StandardCharsets.UTF_8)));
        c.process(resWith("f6", 2_000L, HttpStream.CONTENT_LENGTH, "Content-Type: text/plain"));
        assertThat(lines()).isEmpty();
        c.process(withStream(req("f6", 1_990L), HttpStream.NONE));

        String[] out = lines();
        assertThat(out[0]).contains("GET app.test/api/items?x=1 -> 200  10ms");
        assertThat(out).contains("  < [body 4 bytes]", "  < pong");
        assertThat(out).filteredOn(l -> l.contains("-> 200")).hasSize(1);
    }

    @Test
    void full_gzipTextBodyIsDecompressed() throws Exception {
        ConsoleMonitorConsumer c = consumer(MonitorLevel.FULL);
        byte[] gz = gzip("{\"ok\":true}");
        c.process(withStream(req("f7", 1L), HttpStream.NONE));
        c.process(resWith("f7", 2L, HttpStream.CONTENT_LENGTH, "Content-Type: application/json", "Content-Encoding: gzip"));
        c.process(body(HttpEventType.RES_BODY, "f7", gz));

        assertThat(lines()).contains("  < [body " + gz.length + " bytes, gzip -> 11 bytes]", "  < {\"ok\":true}");
    }

    @Test
    void full_unsupportedEncodingAndBinaryBodies() {
        ConsoleMonitorConsumer c = consumer(MonitorLevel.FULL);
        c.process(withStream(req("f8", 1L), HttpStream.NONE));
        c.process(resWith("f8", 2L, HttpStream.CONTENT_LENGTH, "Content-Type: text/html", "Content-Encoding: br"));
        c.process(body(HttpEventType.RES_BODY, "f8", new byte[]{1, 2, 3}));
        assertThat(lines()).contains("  < [body 3 bytes, br-encoded]");

        captured.reset();
        c.process(withStream(req("f9", 1L), HttpStream.NONE));
        c.process(resWith("f9", 2L, HttpStream.CONTENT_LENGTH, "Content-Type: image/png"));
        c.process(body(HttpEventType.RES_BODY, "f9", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0}));
        assertThat(lines()).contains("  < [body 5 bytes, binary]");
    }

    @Test
    void full_noContentTypeSniffsUtf8Text() {
        ConsoleMonitorConsumer c = consumer(MonitorLevel.FULL);
        c.process(withStream(req("f10", 1L), HttpStream.NONE));
        c.process(resWith("f10", 2L, HttpStream.CONTENT_LENGTH));
        c.process(body(HttpEventType.RES_BODY, "f10", "plain text".getBytes(StandardCharsets.UTF_8)));

        assertThat(lines()).contains("  < [body 10 bytes]", "  < plain text");
    }

    @Test
    void full_longTextIsTruncatedTo2048Bytes() {
        ConsoleMonitorConsumer c = consumer(MonitorLevel.FULL);
        c.process(withStream(req("f11", 1L), HttpStream.NONE));
        c.process(resWith("f11", 2L, HttpStream.CONTENT_LENGTH, "Content-Type: text/plain"));
        c.process(body(HttpEventType.RES_BODY, "f11", "x".repeat(3000).getBytes(StandardCharsets.UTF_8)));

        String[] out = lines();
        assertThat(out).contains("  < [body 3000 bytes]");
        assertThat(out).contains("  < " + "x".repeat(2048));
        assertThat(out[out.length - 1]).isEqualTo("  < ... (952 more bytes)");
    }

    @Test
    void full_wsFramesPrintedImmediately() {
        ConsoleMonitorConsumer c = consumer(MonitorLevel.FULL);
        c.process(wsFrame("w1", "REQ", 1, "hello".getBytes(StandardCharsets.UTF_8)));
        c.process(wsFrame("w1", "RES", 2, new byte[]{1, 2, 3}));
        c.process(wsFrame("w1", "RES", 9, new byte[0]));
        c.process(wsFrame("w1", "REQ", 8, new byte[0]));
        c.process(wsFrame("w1", "REQ", 1, "y".repeat(300).getBytes(StandardCharsets.UTF_8)));

        String[] out = lines();
        assertThat(out).hasSize(5);
        assertThat(out[0]).matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}  \\[ws w1\\] > text: hello");
        assertThat(out[1]).endsWith("[ws w1] < binary 3 bytes");
        assertThat(out[2]).endsWith("[ws w1] < ping");
        assertThat(out[3]).endsWith("[ws w1] > close");
        assertThat(out[4]).endsWith("> text: " + "y".repeat(200) + "...");
    }

    @Test
    void wsFrames_ignoredOutsideFull() {
        ConsoleMonitorConsumer c = consumer(MonitorLevel.HEADERS);
        c.process(wsFrame("w2", "REQ", 1, "hello".getBytes(StandardCharsets.UTF_8)));

        assertThat(lines()).isEmpty();
    }

    private static HttpEvent wsFrame(String rid, String direction, int opcode, byte[] payload) {
        HttpEvent e = new HttpEvent("0", rid, HttpEventType.WS_FRAME);
        e.setWsDirection(direction);
        e.setOpcode(opcode);
        e.setBody(payload);
        return e;
    }

    @Test
    void full_incompleteEntryIsFlushedOnTtl_andHeaderlessEntryDropped() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ConsoleMonitorConsumer c = new ConsoleMonitorConsumer("0", "console", stream, MonitorLevel.FULL, now::get);

        c.process(post("i1", 1_000L, HttpStream.CONTENT_LENGTH));
        c.process(resWith("i1", 1_005L, HttpStream.CONTENT_LENGTH));   // bodies never arrive
        c.process(req("i2", 1_000L));                                  // no response at all
        assertThat(lines()).isEmpty();

        now.addAndGet(30_000);
        c.purge();
        assertThat(lines()).isEmpty();

        now.addAndGet(31_000);
        c.purge();

        String[] out = lines();
        assertThat(out[0]).contains("POST app.test/submit -> 200  5ms  (incomplete)");
        assertThat(out).filteredOn(l -> l.contains("(incomplete)")).hasSize(1);
        assertThat(captured.toString(StandardCharsets.UTF_8)).doesNotContain("/api/items");
    }

    @Test
    void basic_ttlEvictionIsSilent() {
        AtomicLong now = new AtomicLong(1_000_000L);
        ConsoleMonitorConsumer c = new ConsoleMonitorConsumer("0", "console", stream, MonitorLevel.BASIC, now::get);
        c.process(req("b1", 1L));
        now.addAndGet(61_000);
        c.purge();

        assertThat(lines()).isEmpty();
    }
}
