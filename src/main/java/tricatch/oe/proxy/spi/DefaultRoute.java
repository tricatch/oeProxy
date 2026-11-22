package tricatch.oe.proxy.spi;

/**
 * Where a request for a domain without a virtual host goes - see {@link DefaultRouteResolver}.
 * The connection is always HTTPS, with the requested domain as SNI and as the name the
 * certificate is verified for.
 *
 * @param connectHost   host name or IP address to connect to; it may differ from the requested domain
 * @param port          TCP port
 * @param readTimeoutMs how long the upstream may stay silent, or 0 for the proxy's configured read timeout
 */
public record DefaultRoute(String connectHost, int port, int readTimeoutMs) {

    public DefaultRoute {
        if (connectHost == null || connectHost.isBlank()) throw new IllegalArgumentException("connectHost is required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
        if (readTimeoutMs < 0) throw new IllegalArgumentException("readTimeoutMs must not be negative: " + readTimeoutMs);
    }
}
