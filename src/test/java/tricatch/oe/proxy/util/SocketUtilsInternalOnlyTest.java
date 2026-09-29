package tricatch.oe.proxy.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// "Allow internal-network backends only": the address a backend name resolves to is checked before
// the proxy connects, so the restriction cannot be dodged by a name that resolves to a public host.
class SocketUtilsInternalOnlyTest {

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal); // literals only: no DNS lookup
    }

    @Test
    void internalAddresses_areLoopbackPrivateLinkLocalAndTheWildcard() throws Exception {
        for (var internal : new String[] {"127.0.0.1", "127.9.9.9", "10.1.2.3", "172.16.0.1", "172.31.255.254",
                "192.168.0.10", "169.254.10.10", "0.0.0.0", "::1", "fe80::1", "fd12:3456::1"}) {
            assertThat(SocketUtils.isInternalAddress(ip(internal))).as(internal).isTrue();
        }
    }

    @Test
    void publicAddresses_areNotInternal() throws Exception {
        for (var external : new String[] {"8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "192.169.0.1",
                "203.0.113.5", "2606:4700:4700::1111"}) {
            assertThat(SocketUtils.isInternalAddress(ip(external))).as(external).isFalse();
        }
    }

    @Test
    void createHttp_toAPublicAddress_isRefusedBeforeAnyConnection_whenRestricted() {
        assertThatThrownBy(() -> SocketUtils.createHttp("8.8.8.8", 80, 1000, 1000, true))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("not an internal-network address");
    }

    @Test
    void createHttps_toAPublicAddress_isRefusedBeforeAnyConnection_whenRestricted() {
        assertThatThrownBy(() -> SocketUtils.createHttps("example.com", "8.8.8.8", 443, 1000, 1000, false, true))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("not an internal-network address");
    }

    @Test
    void createHttp_toAnInternalAddress_stillConnects_whenRestricted() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            try (var socket = SocketUtils.createHttp("127.0.0.1", server.getLocalPort(), 2000, 2000, true)) {
                assertThat(socket.isConnected()).isTrue();
            }
        }
    }

    @Test
    void whenNotRestricted_aPublicAddressIsNotRefusedByTheCheck() {
        // Nothing listens for us at a documentation address, so the connect itself fails - but with the
        // ordinary timeout/unreachable error, never the restriction's message.
        assertThatThrownBy(() -> SocketUtils.createHttp("203.0.113.5", 80, 300, 300, false))
            .isInstanceOf(IOException.class)
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("internal-network"));
    }
}
