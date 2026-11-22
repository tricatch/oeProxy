package tricatch.oe.proxy.http;

public class HTTP {

    public static final int INIT_HEADER_LINES = 10;
    public static final int INIT_HEADER_LENGTH = 256;
    /** Upper bound on one header line (and on the request/status line). */
    public static final int MAX_HEADER_LENGTH = 64 * 1024;
    /** Upper bound on a whole header block. */
    public static final int MAX_HEADER_BLOCK_LENGTH = 256 * 1024;
    /** Upper bound on the number of lines in a header block. */
    public static final int MAX_HEADER_LINES = 1000;
    public static final int BODY_BUFFER_SIZE = 16 * 1024;
    /** Monitor body display limit (1MB). Bodies exceeding this are not collected for monitor. */
    public static final int MONITOR_BODY_LIMIT = 1024 * 1024;
    public static final int CHUNK_SIZE_LINE_LENGTH = 128;
    /** Upper bound on a single WebSocket frame payload the relay will allocate a buffer for. */
    public static final int MAX_WEBSOCKET_FRAME_LENGTH = 16 * 1024 * 1024;
    public static final byte[] CRLF = "\r\n".getBytes();
    public static final byte SPACE = (byte)0x20;

    public static class HEADER {
        public static final byte[] HOST = "Host".getBytes();
        public static final byte[] CONTENT_LENGTH = "Content-Length".getBytes();
        public static final byte[] CONTENT_TYPE = "Content-Type".getBytes();
        public static final byte[] TRANSFER_ENCODING = "Transfer-Encoding".getBytes();
        public static final byte[] CONNECTION = "Connection".getBytes();
        public static final byte[] UPGRADE = "Upgrade".getBytes();
        public static final byte[] ACCEPT_LANGUAGE = "Accept-Language".getBytes();
        public static final byte[] COOKIE = "Cookie".getBytes();
    }

}
