package tricatch.oe.proxy.exception;

import java.net.URL;

/**
 * Thrown when the proxy successfully connects to the upstream server but no response
 * arrives within the configured read timeout.
 */
public class GatewayTimeoutException extends Exception {

    private final String rid;
    private final String requestHost;
    private final String targetUrl;
    private final String routePath;

    public GatewayTimeoutException(String rid, String requestHost, URL target, String routePath) {
        super("Upstream response timed out");
        this.rid = rid;
        this.requestHost = requestHost;
        this.targetUrl = target.toExternalForm();
        this.routePath = routePath != null ? routePath : "";
    }

    public String getRid() {
        return rid;
    }

    public String getRequestHost() {
        return requestHost;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public String getRoutePath() {
        return routePath;
    }
}
