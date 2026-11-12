package tricatch.oe.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tricatch.oe.proxy.standalone.CaGenerator;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// setBindAddresses opens one HTTPS listener per address: the loopback address and one network
// card's address together, and nothing on the addresses that were not named.
class ReverseProxyServerBindTest {

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        ReverseProxyServer.setBindAddresses();
    }

    @Test
    void bindAddresses_keepTheOrderAndDropNullsAndDuplicates() throws Exception {
        InetAddress lo = InetAddress.getLoopbackAddress();
        ReverseProxyServer.setBindAddresses(lo, null, lo);
        assertThat(ReverseProxyServer.getBindAddresses()).containsExactly(lo);
        assertThat(ReverseProxyServer.getBindAddress()).isEqualTo(lo);

        ReverseProxyServer.setBindAddress(null);
        assertThat(ReverseProxyServer.getBindAddresses()).isEmpty();
        assertThat(ReverseProxyServer.getBindAddress()).isNull();
    }

    @Test
    void oneListenerPerAddress() throws Exception {
        InetAddress lan = siteLocalIpv4();
        assumeTrue(lan != null, "no private IPv4 address on this machine");

        new CaGenerator().generate(dir, "Bind Test CA", true);
        ReverseProxyServer.setCaFiles(dir.resolve("ca.cer"), dir.resolve("ca.pfx"), "");
        int port = freePort();
        ReverseProxyServer.getConfig().getHttps().setPort(port);
        try {
            InetAddress lo = InetAddress.getLoopbackAddress();
            ReverseProxyServer.setBindAddresses(lo, lan);
            ReverseProxyServer.startSslPassServer();

            assertThat(connects(lo, port)).isTrue();
            assertThat(connects(lan, port)).isTrue();

            // Only the loopback address: the network card no longer answers.
            ReverseProxyServer.setBindAddresses(lo);
            ReverseProxyServer.startSslPassServer();
            assertThat(connects(lo, port)).isTrue();
            assertThatThrownBy(() -> new Socket().connect(new InetSocketAddress(lan, port), 1000))
                .isInstanceOf(java.io.IOException.class);
        } finally {
            ReverseProxyServer.getConfig().getHttps().setPort(ReverseProxyServer.HTTPS_PORT);
        }
    }

    /** Whether something accepts a TCP connection there, retrying while the listener starts. */
    private static boolean connects(InetAddress address, int port) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(address, port), 500);
                return true;
            } catch (java.io.IOException e) {
                Thread.sleep(100);
            }
        }
        return false;
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

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
