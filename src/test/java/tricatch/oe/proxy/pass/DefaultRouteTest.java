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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static tricatch.oe.proxy.pass.RelayFixture.*;

// setDefaultRouteResolver, and the rules the library enforces around it, over real TLS.
class DefaultRouteTest {

    private static final String HOST = "app.example.test";
    private static final int READ_TIMEOUT = 1500;

    @TempDir
    static Path dir;
    private static RelayFixture proxy;
    private static int trusted;
    private static int untrusted;
    private static int slow;
    private static String savedTrustStore;
    private static String savedTrustStorePassword;

    private final AtomicInteger resolved = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        savedTrustStore = System.getProperty("javax.net.ssl.trustStore");
        savedTrustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");

        CertificateKeyPair publicCa = new RootCertificateCreator().generateRootCertificate("Trusted Origin CA");
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setCertificateEntry("ca", publicCa.getCertificate());
        Path file = dir.resolve("trust.p12");
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, "test".toCharArray());
        }
        // SocketUtils loads the JVM's default trust store for every connection it verifies.
        System.setProperty("javax.net.ssl.trustStore", file.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "test");

        proxy = new RelayFixture(dir, READ_TIMEOUT);
        trusted = proxy.https(publicCa, always("origin"));
        untrusted = proxy.https(new RootCertificateCreator().generateRootCertificate("Unknown CA"), always("untrusted"));
        slow = proxy.https(publicCa, (in, out, socket) -> {
            while (!head(in).isEmpty()) {
                Thread.sleep(READ_TIMEOUT + 800);
                reply(out, "slow");
            }
        });
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
        ReverseProxyServer.setTrustInternalCertEnabled(true);
        ReverseProxyServer.clearVirtualHosts(OWNER);
    }

    private void resolveTo(int port) {
        resolveTo(new DefaultRoute("127.0.0.1", port, 0));
    }

    private void resolveTo(DefaultRoute destination) {
        ReverseProxyServer.setDefaultRouteResolver(host -> {
            resolved.incrementAndGet();
            return destination;
        });
    }

    private Response get(String sni, String host) throws Exception {
        try (Client client = proxy.client(sni)) {
            return client.get(host, "/");
        }
    }

    @Test
    void withoutAResolver_theProxyStaysVhostOnly() throws Exception {
        assertThat(get(HOST, HOST).status()).contains(" 503 ");

        route(vhost("other.example.test", "http://127.0.0.1:1", "/**"));
        assertThat(get(HOST, HOST).status()).contains(" 404 ");
    }

    @Test
    void aDomainWithoutVhost_isRelayedWhetherOrNotAnyVhostExists() throws Exception {
        resolveTo(trusted);
        assertThat(get(HOST, HOST).body()).isEqualTo("origin");

        route(vhost("other.example.test", "http://127.0.0.1:1", "/**"));
        assertThat(get(HOST, HOST).body()).isEqualTo("origin");
    }

    @Test
    void theResolverSeesTheCanonicalDomain() throws Exception {
        ReverseProxyServer.setDefaultRouteResolver(host -> HOST.equals(host) ? new DefaultRoute("127.0.0.1", trusted, 0) : null);

        for (String host : List.of(HOST + ":443", "App.Example.TEST", HOST + ".")) {
            assertThat(get(HOST, host).body()).as(host).isEqualTo("origin");
        }
    }

    @Test
    void theUpstreamCertificateIsVerified_evenWhereInternalCertificatesAreTrusted() throws Exception {
        ReverseProxyServer.setTrustInternalCertEnabled(true);
        resolveTo(untrusted);

        assertThat(get(HOST, HOST).status()).contains(" 502 ");
    }

    @Test
    void aVerifiedRouteNeverReusesTheUnverifiedSocketOfAVhostWithTheSameUrl() throws Exception {
        route(vhost("dev.example.test", "https://127.0.0.1:" + untrusted, "/**"));
        resolveTo(untrusted);

        try (Client client = proxy.client(HOST)) {
            assertThat(client.get("dev.example.test", "/").body()).isEqualTo("untrusted");
            assertThat(client.get(HOST, "/").status()).contains(" 502 ");
        }
    }

    @Test
    void aHostOtherThanTheSni_is421_alsoLaterOnTheConnection() throws Exception {
        resolveTo(trusted);

        assertThat(get(HOST, "other.example.test").status()).contains(" 421 ");
        assertThat(get(HOST, HOST + ":8443").status()).contains(" 421 ");
        assertThat(resolved).as("the resolver is not even asked").hasValue(0);

        try (Client client = proxy.client(HOST)) {
            assertThat(client.get(HOST, "/").body()).isEqualTo("origin");
            assertThat(client.get("other.example.test", "/").status()).contains(" 421 ");
        }
    }

    @Test
    void theSniCheckHoldsOnAReconnect() throws Exception {
        resolveTo(trusted);

        for (String protocol : List.of("TLSv1.2", "TLSv1.3")) {
            try (Client client = proxy.client(HOST, protocol)) {
                assertThat(client.get(HOST, "/").body()).as(protocol).isEqualTo("origin");
            }
            try (Client client = proxy.client(HOST, protocol)) {
                assertThat(client.get("other.example.test", "/").status()).as(protocol).contains(" 421 ");
            }
        }
    }

    @Test
    void anAbsoluteTargetOrASecondHost_isRejected() throws Exception {
        resolveTo(trusted);
        String[] requests = {
                "GET https://other.example.test/ HTTP/1.1\r\nHost: " + HOST + "\r\n\r\n",
                "GET / HTTP/1.1\r\nHost: " + HOST + "\r\nhOsT: other.example.test\r\n\r\n"};

        for (String request : requests) {
            try (Client client = proxy.client(HOST)) {
                client.send(request);
                assertThat(client.read().status()).as(request).contains(" 400 ");
            }
        }
        assertThat(resolved).hasValue(0);
    }

    @Test
    void aRouteBackToThisProxy_is404() throws Exception {
        for (String address : List.of("127.0.0.1", "0.0.0.0")) {
            resolveTo(new DefaultRoute(address, proxy.port, 0));
            assertThat(get(HOST, HOST).status()).as(address).contains(" 404 ");
        }
    }

    @Test
    void aVhostWins_andItsPathMissIsStill404() throws Exception {
        int backend = proxy.http(always("vhost"));
        route(vhost(HOST, "http://127.0.0.1:" + backend, "/only"));
        resolveTo(trusted);

        try (Client client = proxy.client(HOST)) {
            assertThat(client.get(HOST, "/only").body()).isEqualTo("vhost");
            assertThat(client.get(HOST, "/missing").status()).contains(" 404 ");
        }
        assertThat(resolved).hasValue(0);
    }

    @Test
    void aVhostWrittenInAnotherCase_doesNotFallThroughToTheRealSite() throws Exception {
        int backend = proxy.http(always("vhost"));
        route(vhost("App.Example.Test", "http://127.0.0.1:" + backend, "/**"));
        resolveTo(trusted);

        assertThat(get(HOST, HOST).body()).isEqualTo("vhost");
        assertThat(resolved).hasValue(0);
    }

    @Test
    void vhostsThatFailToLoad_doNotFallThroughToTheRealSite() throws Exception {
        resolveTo(trusted);

        ReverseProxyServer.setVirtualHostsLoader((owner, ip) -> {
            throw new IllegalStateException("store unavailable");
        });
        assertThat(get(HOST, HOST).status()).contains(" 503 ");

        ReverseProxyServer.setVirtualHostsLoader((owner, ip) -> "virtual: [ not yaml");
        assertThat(get(HOST, HOST).status()).contains(" 503 ");
        assertThat(resolved).hasValue(0);
    }

    @Test
    void aResolverThatDeclinesOrFails_is404() throws Exception {
        ReverseProxyServer.setDefaultRouteResolver(host -> null);
        assertThat(get(HOST, HOST).status()).contains(" 404 ");

        ReverseProxyServer.setDefaultRouteResolver(host -> {
            throw new IllegalStateException("broken");
        });
        assertThat(get(HOST, HOST).status()).contains(" 404 ");
    }

    @Test
    void removingTheResolver_appliesToTheNextRequestOfAnOpenConnection() throws Exception {
        resolveTo(trusted);

        try (Client client = proxy.client(HOST)) {
            assertThat(client.get(HOST, "/").body()).isEqualTo("origin");
            ReverseProxyServer.setDefaultRouteResolver(null);
            assertThat(client.get(HOST, "/").status()).matches(".* (404|503) .*");
        }
    }

    @Test
    void anUnresolvableDestination_is502() throws Exception {
        resolveTo(new DefaultRoute("does-not-exist.invalid", 443, 0));

        assertThat(get(HOST, HOST).status()).contains(" 502 ");
    }

    @Test
    void aRouteReadTimeout_outlastsTheConfiguredOne_onEveryRequestOfTheConnection() throws Exception {
        resolveTo(new DefaultRoute("127.0.0.1", slow, READ_TIMEOUT * 4));

        try (Client client = proxy.client(HOST)) {
            assertThat(client.get(HOST, "/").body()).isEqualTo("slow");
            assertThat(client.get(HOST, "/").body()).isEqualTo("slow");
        }
    }

    @Test
    void withoutARouteReadTimeout_theConfiguredOneApplies() throws Exception {
        resolveTo(slow);

        assertThat(get(HOST, HOST).status()).contains(" 504 ");
    }

    @Test
    void aClientThatIsNotOnLoopback_neverGetsADefaultRoute() throws Exception {
        InetAddress lan = siteLocalIpv4();
        assumeTrue(lan != null, "no private IPv4 address on this machine");
        resolveTo(trusted);

        ReverseProxyServer.setBindAddresses(proxy.loopback, lan);
        ReverseProxyServer.startSslPassServer();
        try {
            SSLContext trustAny = SSLContext.getInstance("TLS");
            trustAny.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509ExtendedTrustManager() {
                @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
                @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a, Socket s) { }
                @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) { }
                @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a, Socket s) { }
                @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) { }
                @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            }}, null);
            Socket raw = null;
            for (int i = 0; raw == null; i++) {
                try {
                    raw = new Socket(lan, proxy.port);
                } catch (java.io.IOException e) {
                    if (i >= 50) throw e;
                    Thread.sleep(100); // the listener is still starting
                }
            }
            raw.setSoTimeout(6000);
            SSLSocket socket = (SSLSocket) trustAny.getSocketFactory().createSocket(raw, HOST, 443, true);
            socket.startHandshake();
            try (Client client = new Client(socket)) {
                assertThat(client.get(HOST, "/").status()).contains(" 503 ");
            }
            assertThat(resolved).hasValue(0);
        } finally {
            ReverseProxyServer.setBindAddresses(proxy.loopback);
            ReverseProxyServer.startSslPassServer();
            for (int i = 0; i < 50; i++) {
                try (Socket s = new Socket(proxy.loopback, proxy.port)) {
                    break;
                } catch (java.io.IOException e) {
                    Thread.sleep(100);
                }
            }
        }
    }

    @Test
    void defaultRouteValidatesItsValues() {
        assertThatThrownBy(() -> new DefaultRoute(" ", 443, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultRoute("a.test", 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultRoute("a.test", 443, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static String vhost(String domain, String target, String path) {
        return """
                virtual:
                  - domain: %s
                    location:
                      - host: %s
                        path: [ %s ]
                """.formatted(domain, target, path);
    }

    private static InetAddress siteLocalIpv4() throws Exception {
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a;
            }
        }
        return null;
    }
}
