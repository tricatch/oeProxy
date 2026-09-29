package tricatch.oe.proxy.standalone;

/**
 * How much of each proxied exchange the standalone {@code --monitor} console output shows.
 */
public enum MonitorLevel {
    /** No monitor output. */
    OFF,
    /** One access-log line per request. */
    BASIC,
    /** BASIC plus request and response header lines. */
    HEADERS,
    /** HEADERS plus request/response bodies and WebSocket frames. */
    FULL
}
