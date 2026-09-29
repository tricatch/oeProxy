package tricatch.oe.proxy.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.exception.BadGatewayException;
import tricatch.oe.proxy.exception.GatewayTimeoutException;
import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.HttpStreamWriter;
import tricatch.oe.proxy.spi.ClientHints;
import tricatch.oe.proxy.spi.ErrorPageRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class HtmlUtil {

    private static final Logger logger = LoggerFactory.getLogger(HtmlUtil.class);

    public static void writeBadGatewayResponse(HttpStreamWriter out, BadGatewayException ex, ClientHints hints) throws IOException {
        String html = renderOrNull(r -> r.badGateway(ex, hints));
        if (html == null) {
            html = "<html><body><h1>502 Bad Gateway</h1><p>" + escapeHtml(ex.getMessage()) + "</p></body></html>";
        }
        writeHtml(out, "HTTP/1.1 502 Bad Gateway\r\n", html);
    }

    public static void writeGatewayTimeoutResponse(HttpStreamWriter out, GatewayTimeoutException ex, ClientHints hints) throws IOException {
        String html = renderOrNull(r -> r.gatewayTimeout(ex, hints));
        if (html == null) {
            html = "<html><body><h1>504 Gateway Timeout</h1><p>" + escapeHtml(ex.getMessage()) + "</p></body></html>";
        }
        writeHtml(out, "HTTP/1.1 504 Gateway Timeout\r\n", html);
    }

    public static void writeNotFoundVhostResponse(HttpStreamWriter out, String requestHost, String requestPath, ClientHints hints) throws IOException {
        String host = requestHost != null ? requestHost : "";
        String html = renderOrNull(r -> r.notFoundVhost(host, requestPath, hints));
        if (html == null) {
            html = "<html><body><h1>404 Not Found</h1><p>No proxy route configured for " + escapeHtml(host) + "</p></body></html>";
        }
        writeHtml(out, "HTTP/1.1 404 Not Found\r\n", html);
    }

    public static void writeNoVhostsResponse(HttpStreamWriter out, String clientIp, String identifierHeader, ClientHints hints) throws IOException {
        boolean ipIdentifierEnabled = ReverseProxyServer.isIpIdentifierEnabled();
        String html = renderOrNull(r -> r.noVhosts(clientIp, identifierHeader, ipIdentifierEnabled, hints));
        if (html == null) {
            html = "<html><body><h1>503 Service Unavailable</h1><p>No virtual host configuration is active for this client.</p></body></html>";
        }
        writeHtml(out, "HTTP/1.1 503 Service Unavailable\r\n", html);
    }

    /**
     * Writes a plain 400 response for a request rejected before routing/upstream connection ever
     * happens (malformed request line, ambiguous Content-Length/Transfer-Encoding framing) — no
     * vhost/routing context exists yet at this point, so unlike the other error pages this isn't
     * templated/branded.
     */
    public static void writeBadRequestResponse(HttpStreamWriter out, String reason) throws IOException {
        String html = "<html><body><h1>400 Bad Request</h1><p>" + escapeHtml(reason) + "</p></body></html>";
        writeHtml(out, "HTTP/1.1 400 Bad Request\r\n", html);
    }

    @FunctionalInterface
    private interface RenderCall {
        String render(ErrorPageRenderer renderer);
    }

    private static String renderOrNull(RenderCall call) {
        var renderer = ReverseProxyServer.getErrorPageRenderer();
        if (renderer == null) return null;
        try {
            return call.render(renderer);
        } catch (Exception e) {
            logger.warn("ErrorPageRenderer threw, falling back to built-in page: {}", e.getMessage());
            return null;
        }
    }

    private static void writeHtml(HttpStreamWriter out, String statusLine, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        out.write(statusLine.getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: text/html; charset=utf-8\r\n".getBytes(StandardCharsets.UTF_8));
        out.write("Connection: close\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Length: " + body.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(HTTP.CRLF);
        out.write(body);
        out.flush();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

}
