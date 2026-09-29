package tricatch.oe.proxy.standalone;

import tricatch.oe.proxy.event.HttpEvent;
import tricatch.oe.proxy.event.HttpEventConsumer;
import tricatch.oe.proxy.event.HttpEventType;
import tricatch.oe.proxy.http.io.HeaderLines;
import tricatch.oe.proxy.http.io.HttpStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Standalone --monitor consumer: prints one block per request, joined by request id (rid).
 * The first line is always the access-log summary
 * {@code HH:mm:ss.SSS  METHOD host/path -> STATUS  NNms}; what follows depends on the level:
 * <ul>
 *   <li>BASIC - the summary line only, once the request and response header events are both seen;</li>
 *   <li>HEADERS - same trigger, plus the request header lines ({@code "  > "}) and response header
 *       lines ({@code "  < "});</li>
 *   <li>FULL - same, plus the request and response bodies, printed once both bodies are complete
 *       (or known to be absent). WebSocket frames are printed immediately, one line each.</li>
 * </ul>
 *
 * Several HttpEventManager worker threads drain the queue concurrently, so the events of one rid
 * can be processed in any order (even a response body before the request header). All state of a
 * rid lives in one record that is only ever updated inside ConcurrentHashMap.compute, so the
 * decision "this rid is now complete, print it" is atomic and happens exactly once. Each block is
 * emitted with a single println call so blocks from different worker threads never interleave.
 *
 * Header events carry a snapshot copy of the headers, so reading them here never races with the
 * proxy thread.
 */
public class ConsoleMonitorConsumer implements HttpEventConsumer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    // Bounds for records that never complete (aborted request, upstream failure, relay error).
    private static final int MAX_PENDING = 1000;
    private static final long PENDING_TTL_MS = 60_000;
    private static final long PURGE_INTERVAL_MS = 10_000;

    private static final int BODY_TEXT_LIMIT = 2048;
    private static final int WS_TEXT_LIMIT = 200;
    // Upper bound for decompressing a body for display (bodies are already capped at 1MB upstream).
    private static final int MAX_DECODED_BYTES = 8 * 1024 * 1024;

    /** Everything known about one rid so far; only mutated inside {@code pending.compute}. */
    private static final class Record {
        final long createdAt;
        boolean hasReq;
        long reqTs;
        HeaderLines reqHeaders;
        HttpStream reqStream;
        byte[] reqBody;
        boolean reqBodyDone;
        boolean hasRes;
        long resTs;
        HeaderLines resHeaders;
        HttpStream resStream;
        byte[] resBody;
        boolean resBodyDone;

        Record(long createdAt) {
            this.createdAt = createdAt;
        }

        boolean hasBothHeaders() {
            return hasReq && hasRes;
        }
    }

    private final String clientId;
    private final String channelId;
    private final PrintStream out;
    private final MonitorLevel level;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Record> pending = new ConcurrentHashMap<>();
    private volatile long lastPurge;

    public ConsoleMonitorConsumer(String clientId, String channelId, PrintStream out, MonitorLevel level) {
        this(clientId, channelId, out, level, System::currentTimeMillis);
    }

    // Package-private: lets tests drive the pending-entry TTL with a fake clock.
    ConsoleMonitorConsumer(String clientId, String channelId, PrintStream out, MonitorLevel level, LongSupplier clock) {
        this.clientId = clientId;
        this.channelId = channelId;
        this.out = out;
        this.level = level;
        this.clock = clock;
        this.lastPurge = clock.getAsLong();
    }

    @Override
    public String getClientId() {
        return clientId;
    }

    @Override
    public String getChannelId() {
        return channelId;
    }

    @Override
    public void process(HttpEvent event) {
        try {
            doProcess(event);
        } catch (RuntimeException e) {
            // A monitor must never break event delivery; drop the event.
        }
    }

    private void doProcess(HttpEvent event) {
        if (event.getType() == null || level == MonitorLevel.OFF) return;

        if (event.getType() == HttpEventType.WS_FRAME) {
            if (level == MonitorLevel.FULL) out.println(formatWsFrame(event));
            return;
        }
        if (event.getRid() == null) return;

        boolean full = level == MonitorLevel.FULL;
        switch (event.getType()) {
            case REQ_BODY, RES_BODY -> {
                if (!full) return;
            }
            default -> {
            }
        }

        purgeIfNeeded();

        Record[] done = new Record[1];
        pending.compute(event.getRid(), (rid, existing) -> {
            Record r = existing != null ? existing : new Record(clock.getAsLong());
            switch (event.getType()) {
                case REQ_HEADER -> {
                    r.hasReq = true;
                    r.reqTs = event.getTimestamp();
                    r.reqHeaders = event.getHeaders();
                    r.reqStream = event.getHttpStream();
                }
                case RES_HEADER -> {
                    r.hasRes = true;
                    r.resTs = event.getTimestamp();
                    r.resHeaders = event.getHeaders();
                    r.resStream = event.getHttpStream();
                }
                case REQ_BODY -> {
                    r.reqBody = event.getBody();
                    r.reqBodyDone = true;
                }
                case RES_BODY -> {
                    r.resBody = event.getBody();
                    r.resBodyDone = true;
                }
                default -> {
                }
            }
            if (isComplete(r)) {
                done[0] = r;
                return null;
            }
            return r;
        });

        if (done[0] != null) out.println(format(done[0], false));
    }

    private boolean isComplete(Record r) {
        if (!r.hasBothHeaders()) return false;
        if (level != MonitorLevel.FULL) return true;
        return requestBodyDone(r) && responseBodyDone(r);
    }

    private static boolean requestBodyDone(Record r) {
        return r.reqBodyDone || noBody(r.reqStream);
    }

    private static boolean responseBodyDone(Record r) {
        if (r.resBodyDone || noBody(r.resStream)) return true;
        if ("HEAD".equals(requestMethod(r.reqHeaders))) return true;
        String status = statusCode(r.resHeaders);
        return status.startsWith("1") && status.length() == 3 || "204".equals(status) || "304".equals(status);
    }

    // Unknown (null) stream: don't wait for a body event that may never come.
    private static boolean noBody(HttpStream stream) {
        return stream == null || stream == HttpStream.NONE || stream == HttpStream.NULL || stream == HttpStream.WEBSOCKET;
    }

    // ---- output ----

    private String format(Record r, boolean incomplete) {
        List<String> lines = new ArrayList<>();
        lines.add(summary(r, incomplete));
        if (level == MonitorLevel.BASIC) return lines.get(0);

        appendHeaders(lines, r.reqHeaders, "  > ");
        if (level == MonitorLevel.FULL) appendBody(lines, r.reqBody, r.reqHeaders, "  > ");
        appendHeaders(lines, r.resHeaders, "  < ");
        if (level == MonitorLevel.FULL) appendBody(lines, r.resBody, r.resHeaders, "  < ");
        return String.join(System.lineSeparator(), lines);
    }

    private static String summary(Record r, boolean incomplete) {
        String method = "-";
        String host = "-";
        String path = "-";
        try {
            String line = line(r.reqHeaders, 0);
            if (line != null) {
                String[] parts = line.split(" ");
                if (parts.length >= 2) {
                    method = parts[0];
                    String target = parts[1];
                    if (target.startsWith("/")) {
                        path = target;
                    } else {
                        // Absolute-form (http://host/path) or authority-form (CONNECT host:port).
                        int scheme = target.indexOf("://");
                        String rest = scheme >= 0 ? target.substring(scheme + 3) : target;
                        int slash = rest.indexOf('/');
                        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
                        path = slash >= 0 ? rest.substring(slash) : "";
                        if (!authority.isEmpty()) host = authority;
                    }
                }
            }
            String headerHost = headerValue(r.reqHeaders, "host");
            if (headerHost != null && !headerHost.isEmpty()) host = headerHost;
        } catch (RuntimeException e) {
            // Unparsable header: keep whatever was resolved so far, "-" for the rest.
        }
        long elapsed = Math.max(0, r.resTs - r.reqTs);
        return TIME.format(Instant.ofEpochMilli(r.reqTs)) + "  " + method + " " + host + path
                + " -> " + statusCode(r.resHeaders) + "  " + elapsed + "ms" + (incomplete ? "  (incomplete)" : "");
    }

    private static void appendHeaders(List<String> lines, HeaderLines headers, String prefix) {
        if (headers == null) return;
        for (int i = 0; i < headers.size(); i++) {
            String line = line(headers, i);
            if (line != null) lines.add(prefix + line);
        }
    }

    /** Body block: one size line, then (for text only) the content lines. Nothing for an empty body. */
    private static void appendBody(List<String> lines, byte[] body, HeaderLines headers, String prefix) {
        if (body == null || body.length == 0) return;
        try {
            String encoding = headerValue(headers, "content-encoding");
            encoding = encoding == null ? "" : encoding.trim().toLowerCase(Locale.ROOT);
            String contentType = headerValue(headers, "content-type");

            byte[] data = body;
            String note = "";
            if (!encoding.isEmpty() && !"identity".equals(encoding)) {
                byte[] decoded = null;
                if ("gzip".equals(encoding) || "x-gzip".equals(encoding) || "deflate".equals(encoding)) {
                    decoded = decompress(body, encoding);
                    if (decoded == null) {
                        lines.add(prefix + "[body " + body.length + " bytes, binary]");
                        return;
                    }
                    data = decoded;
                    note = ", " + encoding + " -> " + decoded.length + " bytes";
                } else {
                    lines.add(prefix + "[body " + body.length + " bytes, " + encoding + "-encoded]");
                    return;
                }
            }

            Charset charset = charsetOf(contentType);
            if (!isText(contentType, data)) {
                lines.add(prefix + "[body " + body.length + " bytes" + note + ", binary]");
                return;
            }

            lines.add(prefix + "[body " + body.length + " bytes" + note + "]");
            int shown = Math.min(data.length, BODY_TEXT_LIMIT);
            String text = new String(data, 0, shown, charset);
            for (String textLine : text.split("\\R", -1)) {
                lines.add(prefix + textLine);
            }
            // A trailing newline in the shown text yields one empty last element; drop it.
            if (lines.get(lines.size() - 1).equals(prefix) && text.endsWith("\n")) lines.remove(lines.size() - 1);
            if (data.length > shown) lines.add(prefix + "... (" + (data.length - shown) + " more bytes)");
        } catch (RuntimeException e) {
            lines.add(prefix + "[body " + body.length + " bytes, -]");
        }
    }

    private static byte[] decompress(byte[] body, String encoding) {
        boolean gzip = encoding.endsWith("gzip");
        byte[] result = gzip ? inflate(new GZIPStreamFactory(), body) : inflate(new ZlibStreamFactory(false), body);
        // "deflate" is zlib-wrapped per the RFC, but some servers send a raw deflate stream.
        if (result == null && !gzip) result = inflate(new ZlibStreamFactory(true), body);
        return result;
    }

    private interface StreamFactory {
        InputStream open(InputStream in) throws IOException;
    }

    private static final class GZIPStreamFactory implements StreamFactory {
        @Override
        public InputStream open(InputStream in) throws IOException {
            return new GZIPInputStream(in);
        }
    }

    private static final class ZlibStreamFactory implements StreamFactory {
        private final boolean raw;

        ZlibStreamFactory(boolean raw) {
            this.raw = raw;
        }

        @Override
        public InputStream open(InputStream in) {
            return new InflaterInputStream(in, new Inflater(raw));
        }
    }

    private static byte[] inflate(StreamFactory factory, byte[] body) {
        try (InputStream in = factory.open(new ByteArrayInputStream(body))) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (bos.size() + n > MAX_DECODED_BYTES) return null;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String p = part.trim();
                if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    String name = p.substring("charset=".length()).trim().replace("\"", "");
                    try {
                        return Charset.forName(name);
                    } catch (RuntimeException e) {
                        break;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean isText(String contentType, byte[] data) {
        if (contentType != null && !contentType.isBlank()) {
            int semi = contentType.indexOf(';');
            String type = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase(Locale.ROOT);
            return type.startsWith("text/") || type.endsWith("/json") || type.endsWith("+json")
                    || type.endsWith("/xml") || type.endsWith("+xml") || type.contains("javascript")
                    || type.equals("application/x-www-form-urlencoded");
        }
        // No Content-Type: sniff - valid UTF-8 without control characters other than \t \r \n.
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data, 0, Math.min(data.length, MAX_DECODED_BYTES)))
                    .toString();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x20 && c != '\t' && c != '\r' && c != '\n') return false;
                if (c == 0x7f) return false;
            }
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static String formatWsFrame(HttpEvent event) {
        String arrow = "RES".equals(event.getWsDirection()) ? "<" : ">";
        byte[] payload = event.getBody() == null ? new byte[0] : event.getBody();
        int opcode = event.getOpcode() == null ? -1 : event.getOpcode();
        String what;
        switch (opcode) {
            case 1 -> {
                String text = new String(payload, StandardCharsets.UTF_8).replace('\r', ' ').replace('\n', ' ');
                if (text.length() > WS_TEXT_LIMIT) text = text.substring(0, WS_TEXT_LIMIT) + "...";
                what = "text: " + text;
            }
            case 2 -> what = "binary " + payload.length + " bytes";
            case 8 -> what = "close";
            case 9 -> what = "ping";
            case 10 -> what = "pong";
            case 0 -> what = "continuation " + payload.length + " bytes";
            default -> what = "opcode " + opcode + " " + payload.length + " bytes";
        }
        return TIME.format(Instant.ofEpochMilli(event.getTimestamp())) + "  [ws " + event.getRid() + "] " + arrow + " " + what;
    }

    // ---- pending bound ----

    private void purgeIfNeeded() {
        long now = clock.getAsLong();
        if (pending.size() < MAX_PENDING && now - lastPurge < PURGE_INTERVAL_MS) return;
        purge();
    }

    // Package-private for tests. Evicts stale records; in FULL mode a record that already has both
    // headers is printed as "(incomplete)" instead of being dropped silently.
    void purge() {
        long now = clock.getAsLong();
        lastPurge = now;
        List<Record> evicted = new ArrayList<>();
        for (String rid : pending.keySet()) {
            pending.computeIfPresent(rid, (k, r) -> {
                if (now - r.createdAt > PENDING_TTL_MS) {
                    evicted.add(r);
                    return null;
                }
                return r;
            });
        }
        // Still full of fresh entries: drop everything rather than grow without bound.
        if (pending.size() >= MAX_PENDING) {
            for (String rid : pending.keySet()) {
                pending.computeIfPresent(rid, (k, r) -> {
                    evicted.add(r);
                    return null;
                });
            }
        }
        if (level == MonitorLevel.FULL) {
            for (Record r : evicted) {
                if (r.hasBothHeaders()) out.println(format(r, true));
            }
        }
    }

    // ---- header helpers ----

    private static String requestMethod(HeaderLines headers) {
        try {
            String line = line(headers, 0);
            if (line == null) return "";
            int space = line.indexOf(' ');
            return space > 0 ? line.substring(0, space) : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String statusCode(HeaderLines headers) {
        try {
            String line = line(headers, 0);
            if (line != null) {
                // "HTTP/1.1 200 OK"
                String[] parts = line.split(" ");
                if (parts.length >= 2 && !parts[1].isEmpty()) return parts[1];
            }
        } catch (RuntimeException e) {
            // Unparsable header: "-".
        }
        return "-";
    }

    private static String line(HeaderLines headers, int index) {
        if (headers == null || index >= headers.size()) return null;
        tricatch.oe.proxy.http.io.ByteBuffer buffer = headers.get(index);
        return buffer == null ? null : buffer.toString().trim();
    }

    private static String headerValue(HeaderLines headers, String name) {
        if (headers == null) return null;
        String prefix = name.toLowerCase(Locale.ROOT) + ":";
        for (int i = 1; i < headers.size(); i++) {
            String line = line(headers, i);
            if (line != null && line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }
}
