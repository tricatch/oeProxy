package tricatch.oe.proxy.spi;

import tricatch.oe.proxy.exception.BadGatewayException;
import tricatch.oe.proxy.exception.GatewayTimeoutException;

/**
 * Lets the embedding application render its own branded error pages instead of this library's
 * built-in plain-HTML fallback. Registered via {@code ReverseProxyServer.setErrorPageRenderer}.
 */
public interface ErrorPageRenderer {

    /** Each method returns the full HTML body, or null to fall back to the built-in plain page. */
    default String badGateway(BadGatewayException ex, ClientHints hints) { return null; }
    default String gatewayTimeout(GatewayTimeoutException ex, ClientHints hints) { return null; }
    default String notFoundVhost(String requestHost, String requestPath, ClientHints hints) { return null; }
    default String noVhosts(String clientIp, String identifierHeader, boolean ipIdentifierEnabled, ClientHints hints) { return null; }
}
