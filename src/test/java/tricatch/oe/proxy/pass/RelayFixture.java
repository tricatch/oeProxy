package tricatch.oe.proxy.pass;

import io.github.tricatch.gotpache.cert.CertificateKeyPair;
import io.github.tricatch.gotpache.cert.KeyTool;
import io.github.tricatch.gotpache.cert.RootCertificateCreator;
import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.cert.MultiDomainCertKeyManager;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A real HTTPS listener of the proxy on a random loopback port, with scripted backends behind it. */
final class RelayFixture implements AutoCloseable {

    static final long OWNER = 0L;

    @FunctionalInterface
    interface Backend {
        void serve(InputStream in, OutputStream out, Socket socket) throws Exception;
    }

    record Response(String status, String headers, String body) {}

    final InetAddress loopback;
    final int port;
    private final int connectTimeout;
    private final int readTimeout;
    private final SSLContext clientContext;
    private final List<ServerSocket> backends = new ArrayList<>();

    RelayFixture(Path dir, int readTimeout) throws Exception {
        loopback = InetAddress.getByName("127.0.0.1");
        CertificateKeyPair ca = new RootCertificateCreator().generateRootCertificate("Relay Test CA");
        KeyTool tool = new KeyTool();
        tool.writeCertificate(ca.getCertificate(), dir.toString(), "ca.cer");
        tool.writePrivateKey(ca.getPrivateKey(), dir.toString(), "ca.key");
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }

        var https = ReverseProxyServer.getConfig().getHttps();
        this.connectTimeout = https.getConnectTimeout();
        this.readTimeout = https.getReadTimeout();
        https.setPort(port);
        https.setReadTimeout(readTimeout);
        ReverseProxyServer.setBindAddresses(loopback);
        ReverseProxyServer.setCaFiles(dir.resolve("ca.cer"), dir.resolve("ca.key"), "");
        ReverseProxyServer.setDefaultOwner(OWNER);
        ReverseProxyServer.startSslPassServer();
        clientContext = clientContext(ca.getCertificate());

        for (int i = 0; ; i++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(loopback, port), 500);
                break;
            } catch (IOException e) {
                if (i >= 50) throw e;
                Thread.sleep(100);
            }
        }
    }

    static SSLContext clientContext(X509Certificate ca) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setCertificateEntry("ca", ca);
        TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
        factory.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    int http(Backend backend) throws Exception {
        return serve(new ServerSocket(0, 50, loopback), backend);
    }

    int https(CertificateKeyPair ca, Backend backend) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[]{new MultiDomainCertKeyManager(ca.getCertificate(), ca.getPrivateKey())}, null, null);
        return serve(context.getServerSocketFactory().createServerSocket(0, 50, loopback), backend);
    }

    private int serve(ServerSocket server, Backend backend) {
        backends.add(server);
        Thread.ofPlatform().daemon().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread.ofVirtual().start(() -> {
                        try (socket) {
                            backend.serve(socket.getInputStream(), socket.getOutputStream(), socket);
                        } catch (Exception ignored) {
                            // the proxy or the test closed the connection
                        }
                    });
                } catch (IOException e) {
                    return;
                }
            }
        });
        return server.getLocalPort();
    }

    static void route(String yaml) throws Exception {
        ReverseProxyServer.setVirtualHosts(OWNER, yaml);
    }

    Client client(String sni) throws Exception {
        return client(sni, "TLSv1.3");
    }

    Client client(String sni, String protocol) throws Exception {
        Socket raw = new Socket(loopback, port);
        raw.setSoTimeout(6000);
        SSLSocket socket = (SSLSocket) clientContext.getSocketFactory().createSocket(raw, sni, 443, true);
        socket.setEnabledProtocols(new String[]{protocol});
        socket.startHandshake();
        return new Client(socket);
    }

    static final class Client implements AutoCloseable {
        final SSLSocket socket;
        final InputStream in;
        final OutputStream out;

        Client(SSLSocket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        Response get(String host, String path) throws IOException {
            send("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\n\r\n");
            return read();
        }

        void send(String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        Response read() throws IOException {
            String head = head(in);
            if (head.isEmpty()) return new Response("closed", "", "");
            int length = contentLength(head);
            String body = new String(in.readNBytes(length), StandardCharsets.UTF_8);
            return new Response(head.substring(0, head.indexOf("\r\n")), head, body);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    static String head(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) >= 0; ) {
            b.append((char) c);
            int n = b.length();
            if (n >= 4 && b.substring(n - 4).equals("\r\n\r\n")) return b.toString();
        }
        return "";
    }

    static int contentLength(String head) {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                return Integer.parseInt(line.substring(15).trim());
            }
        }
        return 0;
    }

    static void reply(OutputStream out, String body) throws IOException {
        reply(out, "", body);
    }

    static void reply(OutputStream out, String headers, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\n" + headers + "Content-Length: " + bytes.length + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    /** Answers every request of a keep-alive connection with the same body. */
    static Backend always(String body) {
        return (in, out, socket) -> {
            for (String head; !(head = head(in)).isEmpty(); ) {
                in.readNBytes(contentLength(head));
                reply(out, body);
            }
        };
    }

    @Override
    public void close() throws IOException {
        for (ServerSocket s : backends) s.close();
        ReverseProxyServer.clearVirtualHosts(OWNER);
        ReverseProxyServer.setDefaultOwner(null);
        ReverseProxyServer.setBindAddresses();
        var https = ReverseProxyServer.getConfig().getHttps();
        https.setPort(ReverseProxyServer.HTTPS_PORT);
        https.setConnectTimeout(connectTimeout);
        https.setReadTimeout(readTimeout);
    }
}
