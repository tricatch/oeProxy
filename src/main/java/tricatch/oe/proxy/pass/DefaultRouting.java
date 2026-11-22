package tricatch.oe.proxy.pass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.exception.MisdirectedRequestException;
import tricatch.oe.proxy.http.io.HeaderLines;
import tricatch.oe.proxy.http.io.HttpRequest;
import tricatch.oe.proxy.server.VirtualPath;
import tricatch.oe.proxy.spi.DefaultRoute;
import tricatch.oe.proxy.spi.DefaultRouteResolver;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.util.List;

/** The rules the library enforces around a {@link DefaultRouteResolver}'s answer. */
final class DefaultRouting {

    private static final Logger logger = LoggerFactory.getLogger(DefaultRouting.class);

    private DefaultRouting() {}

    /**
     * Whether this connection may use a default route at all. Only loopback clients may: the
     * listener can face the network, and must not turn into an open proxy there.
     */
    static boolean isAvailable(Socket client) {
        return ReverseProxyServer.getDefaultRouteResolver() != null
                && client instanceof SSLSocket
                && client.getInetAddress() != null
                && client.getInetAddress().isLoopbackAddress();
    }

    /**
     * The default route of a request for a domain without a virtual host, or null for none.
     *
     * @throws MisdirectedRequestException when Host is not the SNI of the connection
     * @throws IllegalArgumentException    when the upstream could read another host out of the request
     */
    static DefaultRoute resolve(Socket client, HttpRequest request) throws MisdirectedRequestException {
        if (request.getHost() == null || !isAvailable(client)) return null;

        // An absolute-form target or a second Host would get around the SNI check below: a server
        // may route by either of them rather than by the Host header that was checked.
        String target = request.getPath();
        if ("CONNECT".equals(request.getMethod())) {
            // The upstream would turn the connection into a raw tunnel, past every check here.
            throw new IllegalArgumentException("CONNECT is not accepted on the default route");
        }
        if (!target.startsWith("/") && !"*".equals(target)) {
            throw new IllegalArgumentException("Only origin-form request targets are accepted on the default route");
        }
        if (countHostHeaders(request.getHeaders()) != 1) {
            throw new IllegalArgumentException("Exactly one Host header is required on the default route");
        }

        String host = HostNames.canonical(request.getHost());
        String sni = requestedServerName((SSLSocket) client);
        // A tunnel opened for one host must not be steered to another by changing the Host header.
        // ASCII only: toLowerCase() folds U+212A to "k", but the upstream gets the bytes as sent.
        if (sni == null || !isAscii(request.getHost()) || !host.equals(HostNames.canonical(sni))) {
            throw new MisdirectedRequestException("Host " + request.getHost() + " does not match SNI " + sni);
        }

        DefaultRouteResolver resolver = ReverseProxyServer.getDefaultRouteResolver();
        if (resolver == null) return null;
        try {
            return resolver.resolve(host);
        } catch (RuntimeException e) {
            logger.warn("DefaultRouteResolver failed for {}: {}", host, e.toString());
            return null;
        }
    }

    /** The route in the form the relay and the error pages work with. */
    @SuppressWarnings("deprecation") // URI refuses names such as "my_service" that resolve fine
    static VirtualPath toVirtualPath(DefaultRoute route) throws IOException {
        VirtualPath path = new VirtualPath();
        path.setPath("/**");
        path.setTarget(new URL("https", route.connectHost(), route.port(), ""));
        path.setAddHeader(List.of());
        path.setRemoveHeader(List.of());
        return path;
    }

    /** Whether connecting to {@code destination} would reach this proxy's own listener. */
    static boolean isThisProxy(InetSocketAddress destination) {
        InetAddress address = destination.getAddress();
        if (address == null || destination.getPort() != ReverseProxyServer.getConfig().getHttps().getPort()) return false;
        if (address.isAnyLocalAddress()) return true;
        if (!address.isLoopbackAddress() && !isOwnAddress(address)) return false;
        List<InetAddress> bound = ReverseProxyServer.getBindAddresses();
        return bound.isEmpty() || bound.stream().anyMatch(b -> b.isAnyLocalAddress() || b.equals(address));
    }

    private static boolean isOwnAddress(InetAddress address) {
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (SocketException e) {
            return true; // cannot tell: refuse rather than risk a loop
        }
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) >= 0x80) return false;
        }
        return true;
    }

    private static int countHostHeaders(HeaderLines headers) {
        int count = 0;
        for (int i = 1; i < headers.size(); i++) {
            String line = headers.get(i).toString();
            int colon = line.indexOf(':');
            if (colon > 0 && "host".equalsIgnoreCase(line.substring(0, colon))) count++;
        }
        return count;
    }

    // Read after the handshake, so it holds for a resumed session as well.
    private static String requestedServerName(SSLSocket socket) {
        if (!(socket.getSession() instanceof ExtendedSSLSession session)) return null;
        for (SNIServerName name : session.getRequestedServerNames()) {
            if (name instanceof SNIHostName hostName) return hostName.getAsciiName();
        }
        return null;
    }
}
