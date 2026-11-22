package tricatch.oe.proxy.pass;

import io.github.tricatch.gotpache.cert.CertificateKeyPair;
import io.github.tricatch.gotpache.cert.RootCertificateCreator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.spi.DefaultRoute;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static tricatch.oe.proxy.pass.RelayFixture.*;

// What must not get past one request's checks, and the timing edges of a keep-alive connection.
class RelayBoundaryTest {

    private static final String HOST = "edge.example.test";
    private static final int READ_TIMEOUT = 2000;
    private static final String UPGRADE = "Upgrade: websocket\r\nConnection: Upgrade\r\n";
    private static final String SWITCHING = "HTTP/1.1 101 Switching Protocols\r\n" + UPGRADE + "\r\n";

    @TempDir
    static Path dir;
    private static RelayFixture proxy;
    private static CertificateKeyPair trustedCa;
    private static String savedTrustStore;
    private static String savedTrustStorePassword;

    private final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        savedTrustStore = System.getProperty("javax.net.ssl.trustStore");
        savedTrustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");
        trustedCa = new RootCertificateCreator().generateRootCertificate("Boundary Origin CA");
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setCertificateEntry("ca", trustedCa.getCertificate());
        Path file = dir.resolve("trust.p12");
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, "test".toCharArray());
        }
        System.setProperty("javax.net.ssl.trustStore", file.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "test");
        proxy = new RelayFixture(dir, READ_TIMEOUT);
    }

    @AfterAll
    static void stop() throws Exception {
        proxy.close();
        restore("javax.net.ssl.trustStore", savedTrustStore);
        restore("javax.net.ssl.trustStorePassword", savedTrustStorePassword);
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    @BeforeEach
    void noVhosts() {
        ReverseProxyServer.setVirtualHostsLoader((owner, ip) -> null);
        ReverseProxyServer.clearVirtualHosts(OWNER);
    }

    @AfterEach
    void reset() {
        ReverseProxyServer.setDefaultRouteResolver(null);
        ReverseProxyServer.setVirtualHostsLoader(null);
        ReverseProxyServer.clearVirtualHosts(OWNER);
    }

    private static void vhost(String scheme, int port, String path) throws Exception {
        route("""
                virtual:
                  - domain: %s
                    location:
                      - host: %s://127.0.0.1:%d
                        path: [ %s ]
                """.formatted(HOST, scheme, port, path));
    }

    /** A keep-alive backend that records each request head and never upgrades. */
    private Backend recording() {
        return (in, out, socket) -> {
            for (String head; !(head = head(in)).isEmpty(); ) {
                received.add(head);
                in.readNBytes(contentLength(head));
                reply(out, "ok");
            }
        };
    }

    private static String request(String line, String headers) {
        return line + " HTTP/1.1\r\nHost: " + HOST + "\r\n" + headers + "\r\n";
    }

    /** Sends and returns the status line, or "closed". */
    private static String status(Client client, String request) {
        try {
            client.send(request);
            return client.read().status();
        } catch (IOException e) {
            return "closed";
        }
    }

    // ── Upgrade ────────────────────────────────────────────────

    @Test
    void afterARefusedUpgrade_theNextRequestIsStillCheckedAgainstTheSni() throws Exception {
        int origin = proxy.https(trustedCa, recording());
        ReverseProxyServer.setDefaultRouteResolver(host -> new DefaultRoute("127.0.0.1", origin, 0));

        try (Client client = proxy.client(HOST)) {
            assertThat(status(client, request("GET /ws", UPGRADE))).contains(" 200 ");
            assertThat(status(client, "GET /next HTTP/1.1\r\nHost: other.example.test\r\n\r\n")).contains(" 421 ");
        }
        assertThat(received).hasSize(1);
    }

    @Test
    void afterARefusedUpgrade_theNextRequestIsStillRouted() throws Exception {
        vhost("http", proxy.http(recording()), "/ws");

        try (Client client = proxy.client(HOST)) {
            assertThat(status(client, request("GET /ws", UPGRADE))).contains(" 200 ");
            assertThat(status(client, request("GET /not-routed", ""))).contains(" 404 ");
        }
        assertThat(received).hasSize(1);
    }

    @Test
    void afterARefusedUpgrade_otherHostsAreNotRelayed() throws Exception {
        int origin = proxy.https(trustedCa, recording());
        ReverseProxyServer.setDefaultRouteResolver(host -> new DefaultRoute("127.0.0.1", origin, 0));

        try (Client client = proxy.client(HOST)) {
            assertThat(status(client, request("GET /ws", UPGRADE))).contains(" 200 ");
            client.send("GET /next HTTP/1.1\r\nHost: other.example.test\r\n\r\n" + "a".repeat(400));
            Thread.sleep(700);
        }
        assertThat(received).noneMatch(head -> head.contains("other.example.test"));
    }

    @Test
    void anUpgradeWithABody_isRejected() throws Exception {
        vhost("http", proxy.http(recording()), "/**");

        for (String framing : List.of("Content-Length: 4\r\n", "Transfer-Encoding: chunked\r\n")) {
            try (Client client = proxy.client(HOST)) {
                assertThat(status(client, request("GET /ws", UPGRADE + framing))).as(framing).contains(" 400 ");
            }
        }
        assertThat(received).isEmpty();
    }

    @Test
    void aSwitchNobodyAskedFor_is502() throws Exception {
        vhost("http", proxy.http((in, out, socket) -> {
            head(in);
            out.write(SWITCHING.getBytes());
            out.flush();
            in.read();
        }), "/**");

        try (Client client = proxy.client(HOST)) {
            assertThat(status(client, request("GET /", ""))).contains(" 502 ");
        }
    }

    @Test
    void webSocketFrames_flowBothWays() throws Exception {
        vhost("http", proxy.http((in, out, socket) -> {
            head(in);
            out.write(SWITCHING.getBytes());
            out.flush();
            byte[] frame = in.readNBytes(8); // masked "hi"
            out.write(new byte[]{(byte) 0x81, 2, (byte) (frame[6] ^ frame[2]), (byte) (frame[7] ^ frame[3])});
            out.flush();
            in.read();
        }), "/**");

        try (Client client = proxy.client(HOST)) {
            assertThat(status(client, request("GET /ws", UPGRADE))).contains(" 101 ");
            client.out.write(new byte[]{(byte) 0x81, (byte) 0x82, 1, 2, 3, 4, 'h' ^ 1, 'i' ^ 2});
            client.out.flush();
            assertThat(client.in.readNBytes(4)).containsExactly(0x81, 2, 'h', 'i');
        }
    }

    @Test
    void aWebSocketBackendThatDisappears_closesTheClient() throws Exception {
        vhost("http", proxy.http((in, out, socket) -> {
            head(in);
            out.write(SWITCHING.getBytes());
            out.flush();
            Thread.sleep(200); // then gone, without a close frame
        }), "/**");

        try (Client client = proxy.client(HOST)) {
            assertThat(status(client, request("GET /ws", UPGRADE))).contains(" 101 ");
            assertThat(client.in.read()).isEqualTo(-1);
        }
    }

    @Test
    void aCloseFrameFromTheClient_getsTheBackendsAnswer() throws Exception {
        vhost("http", proxy.http((in, out, socket) -> {
            head(in);
            out.write(SWITCHING.getBytes());
            out.flush();
            in.readNBytes(8);
            Thread.sleep(150);
            out.write(new byte[]{(byte) 0x88, 2, 3, (byte) 0xE8});
            out.flush();
        }), "/**");

        try (Client client = proxy.client(HOST)) {
            assertThat(status(client, request("GET /ws", UPGRADE))).contains(" 101 ");
            client.out.write(new byte[]{(byte) 0x88, (byte) 0x82, 0, 0, 0, 0, 3, (byte) 0xE8});
            client.out.flush();
            assertThat(client.in.readNBytes(4)).containsExactly(0x88, 2, 3, 0xE8);
            long from = System.nanoTime();
            assertThat(client.in.read()).isEqualTo(-1);
            assertThat((System.nanoTime() - from) / 1_000_000).as("closed as soon as both directions ended").isLessThan(500);
        }
    }

    // ── Framing and header syntax ──────────────────────────────

    @Test
    void framingTwoParsersCouldReadDifferently_isRejected() throws Exception {
        vhost("http", proxy.http(recording()), "/**");
        // client.send() writes ISO-8859-1: each char below is one byte on the wire.
        String kelvinK = new String("\u212A".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        List<String> bad = List.of(
                "Content-Length: +0\r\n",
                "Content-Length: -0\r\n",
                "Content-Length:\r\n",
                "Transfer-Encoding: chun" + kelvinK + "ed\r\n",
                "X-A: a\rHost: other.example.test\r\n",
                "X-A: a\u0000b\r\n",
                "Transfer-Encoding\u000b: chunked\r\n");

        for (String headers : bad) {
            try (Client client = proxy.client(HOST)) {
                assertThat(status(client, request("GET /", headers))).as(headers).contains(" 400 ");
            }
        }
        assertThat(received).isEmpty();
    }

    @Test
    void aBareCarriageReturnInAChunkSizeLine_isNotForwarded() throws Exception {
        AtomicInteger chunkBytes = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        vhost("http", proxy.http((in, out, socket) -> {
            head(in);
            socket.setSoTimeout(1000);
            try {
                while (in.read() >= 0) chunkBytes.incrementAndGet();
            } finally {
                done.countDown();
            }
        }), "/**");

        try (Client client = proxy.client(HOST)) {
            client.send(request("POST /", "Transfer-Encoding: chunked\r\n") + "2\rX\r\nhi\r\n0\r\n\r\n");
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(chunkBytes).hasValue(0);
    }

    @Test
    void requestLinesTwoParsersCouldReadDifferently_areRejected() throws Exception {
        vhost("http", proxy.http(recording()), "/**");
        List<String> bad = List.of(
                "GET /public /../admin HTTP/1.1\r\nHost: " + HOST + "\r\n\r\n",
                "GET /public\t/admin HTTP/1.1\r\nHost: " + HOST + "\r\n\r\n",
                "GET / HTTP/1.1 \r\nHost: " + HOST + "\r\n\r\n",
                "POST / HTTP/1.0\r\nHost: " + HOST + "\r\nTransfer-Encoding: chunked\r\n\r\n");

        for (String text : bad) {
            try (Client client = proxy.client(HOST)) {
                assertThat(status(client, text)).as(text).contains(" 400 ");
            }
        }
        assertThat(received).isEmpty();
    }

    @Test
    void aBareCarriageReturnInATrailer_isNotForwarded() throws Exception {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        vhost("http", proxy.http((in, out, socket) -> {
            head(in);
            socket.setSoTimeout(1000);
            try {
                for (int b; (b = in.read()) >= 0; ) body.write(b);
            } finally {
                done.countDown();
            }
        }), "/**");

        try (Client client = proxy.client(HOST)) {
            client.send(request("POST /", "Transfer-Encoding: chunked\r\n")
                    + "0\r\nX: a\r\rGET /admin HTTP/1.1\rHost: x\r\r\n\r\n");
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(body.toString()).doesNotContain("/admin");
    }

    // ── Default route ──────────────────────────────────────────

    @Test
    void connectIsRejectedOnTheDefaultRoute() throws Exception {
        int origin = proxy.https(trustedCa, recording());
        ReverseProxyServer.setDefaultRouteResolver(host -> new DefaultRoute("127.0.0.1", origin, 0));

        try (Client client = proxy.client(HOST)) {
            assertThat(status(client, request("CONNECT /", ""))).contains(" 400 ");
        }
        assertThat(received).isEmpty();
    }

    @Test
    void aNonAsciiHost_doesNotPassTheSniCheck() throws Exception {
        int origin = proxy.https(trustedCa, recording());
        ReverseProxyServer.setDefaultRouteResolver(host -> new DefaultRoute("127.0.0.1", origin, 0));
        String lookalike = new String("\u212A.example.test".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);

        try (Client client = proxy.client("k.example.test")) {
            assertThat(status(client, "GET / HTTP/1.1\r\nHost: " + lookalike + "\r\n\r\n")).contains(" 421 ");
        }
        assertThat(received).isEmpty();
    }

    @Test
    void aConnectHostWithAnUnderscore_getsAnAnswer() throws Exception {
        ReverseProxyServer.setDefaultRouteResolver(host -> new DefaultRoute("no_such_host.invalid", 443, 0));

        try (Client client = proxy.client(HOST)) {
            assertThat(status(client, request("GET /", ""))).contains(" 502 ");
        }
    }

    // ── Read timeout ───────────────────────────────────────────

    @Test
    void theResponseTimeoutStartsWithTheRequest_notWhenTheConnectionWentIdle() throws Exception {
        Backend slowSecond = (in, out, socket) -> {
            for (int i = 0; !head(in).isEmpty(); i++) {
                if (i > 0) Thread.sleep(1200);
                reply(out, "ok");
            }
        };
        for (String scheme : List.of("http", "https")) {
            vhost(scheme, "http".equals(scheme) ? proxy.http(slowSecond) : proxy.https(trustedCa, slowSecond), "/**");
            try (Client client = proxy.client(HOST)) {
                assertThat(client.get(HOST, "/").status()).contains(" 200 ");
                Thread.sleep(1300); // idle for less than the timeout; idle + processing is more
                assertThat(client.get(HOST, "/").status()).as(scheme).contains(" 200 ");
            }
        }
    }

    @Test
    void anUploadLongerThanTheTimeout_isNotA504() throws Exception {
        for (String scheme : List.of("http", "https")) {
            vhost(scheme, "http".equals(scheme) ? proxy.http(recording()) : proxy.https(trustedCa, recording()), "/**");
            try (Client client = proxy.client(HOST)) {
                client.send(request("POST /", "Content-Length: 8\r\n"));
                for (int i = 0; i < 8; i++) {
                    Thread.sleep(400);
                    client.send("x");
                }
                assertThat(client.read().status()).as(scheme).contains(" 200 ");
            }
        }
    }

    @Test
    void aSilentBackend_stillTimesOutOnTime() throws Exception {
        vhost("http", proxy.http((in, out, socket) -> {
            head(in);
            Thread.sleep(6000);
        }), "/**");

        try (Client client = proxy.client(HOST)) {
            long from = System.nanoTime();
            assertThat(client.get(HOST, "/").status()).contains(" 504 ");
            assertThat((System.nanoTime() - from) / 1_000_000).isBetween(READ_TIMEOUT - 100L, READ_TIMEOUT + 1000L);
        }
    }

    // ── Closing TLS 1.2 sockets another thread is reading ──────

    @Test
    void overTls12_anIdleUpstreamCloseReachesTheClientAtOnce() throws Exception {
        vhost("http", proxy.http((in, out, socket) -> {
            head(in);
            reply(out, "ok");
            Thread.sleep(200);
        }), "/**");

        try (Client client = proxy.client(HOST, "TLSv1.2")) {
            assertThat(client.get(HOST, "/").body()).isEqualTo("ok");
            long from = System.nanoTime();
            assertThat(client.in.read()).isEqualTo(-1);
            assertThat((System.nanoTime() - from) / 1_000_000).isLessThan(1500);
        }
    }

    @Test
    void overTls12_changingTheTargetDoesNotWaitForASilentOldUpstream() throws Exception {
        int old = proxy.https(trustedCa, (in, out, socket) -> {
            ((SSLSocket) socket).setEnabledProtocols(new String[]{"TLSv1.2"});
            head(socket.getInputStream());
            reply(socket.getOutputStream(), "old");
            Thread.sleep(5000); // never answers close_notify
        });
        int next = proxy.http(always("next"));
        route("""
                virtual:
                  - domain: %s
                    location:
                      - host: https://127.0.0.1:%d
                        path: [ /old ]
                      - host: http://127.0.0.1:%d
                        path: [ /next ]
                """.formatted(HOST, old, next));

        try (Client client = proxy.client(HOST)) {
            assertThat(client.get(HOST, "/old").body()).isEqualTo("old");
            long from = System.nanoTime();
            assertThat(client.get(HOST, "/next").body()).isEqualTo("next");
            assertThat((System.nanoTime() - from) / 1_000_000).isLessThan(1000);
        }
    }
}
