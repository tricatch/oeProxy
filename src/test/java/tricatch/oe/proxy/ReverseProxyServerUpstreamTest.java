package tricatch.oe.proxy;

import org.junit.jupiter.api.Test;
import tricatch.oe.proxy.server.VirtualHosts;
import tricatch.oe.proxy.server.VirtualPath;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// When "Allow internal-network backends only" is on, a virtual host config whose backend resolves to
// a public address is refused as it is applied (the same path the oeProxy editor's save goes through).
class ReverseProxyServerUpstreamTest {

    private static VirtualHosts hosts(String domain, String backend) throws Exception {
        var path = new VirtualPath();
        path.setPath("/**");
        path.setTarget(URI.create(backend).toURL());
        var virtualHosts = new VirtualHosts();
        virtualHosts.put(domain, List.of(path));
        return virtualHosts;
    }

    @Test
    void aPublicBackend_isRefused_whenRestricted() throws Exception {
        assertThatThrownBy(() -> ReverseProxyServer.requireInternalUpstreams(hosts("app.oe", "http://8.8.8.8:8080"), true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("app.oe")
            .hasMessageContaining("8.8.8.8")
            .hasMessageContaining("not an internal-network address");
    }

    @Test
    void internalBackends_areAccepted_whenRestricted() throws Exception {
        for (var backend : new String[] {"http://127.0.0.1:3000", "https://10.0.0.5", "http://192.168.1.20:8080",
                "http://[::1]:9000", "http://localhost:8080"}) {
            assertThatCode(() -> ReverseProxyServer.requireInternalUpstreams(hosts("app.oe", backend), true))
                .as(backend).doesNotThrowAnyException();
        }
    }

    @Test
    void anyBackend_isAccepted_whenNotRestricted() throws Exception {
        assertThatCode(() -> ReverseProxyServer.requireInternalUpstreams(hosts("app.oe", "http://8.8.8.8"), false))
            .doesNotThrowAnyException();
    }

    @Test
    void aNameThatDoesNotResolve_isLeftToTheConnectTimeCheck() throws Exception {
        // A dev backend that is simply down must still be saveable; SocketUtils checks it once it resolves.
        assertThatCode(() -> ReverseProxyServer.requireInternalUpstreams(hosts("app.oe", "http://no-such-host.invalid"), true))
            .doesNotThrowAnyException();
    }
}
