package tricatch.oe.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tricatch.oe.proxy.exception.NotFoundProxyVirtualHostsException;
import tricatch.oe.proxy.server.VirtualHosts;
import tricatch.oe.proxy.spi.IdentifierCodec;
import tricatch.oe.proxy.spi.PlainIdentifierCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ReverseProxyServer.setDefaultOwner (spec §2a): the standalone CLI's single-owner fallback in
// resolveIdentifier(). The multi-owner embedding case never calls this, so null (the default)
// must leave the existing "unidentified request" behavior completely unchanged.
class ReverseProxyServerDefaultOwnerTest {

    private static final Long OWNER = 0L;
    private static final String VHOST_YAML = """
            virtual:
              - domain: app.test
                location:
                  - host: http://127.0.0.1:8080
                    path: [ /** ]
            """;

    @AfterEach
    void resetState() {
        ReverseProxyServer.setDefaultOwner(null);
        ReverseProxyServer.clearVirtualHosts(OWNER);
        ReverseProxyServer.setIdentifierHeaderName(ReverseProxyServer.DEFAULT_IDENTIFIER_HEADER);
        ReverseProxyServer.setIdentifierCodec(new PlainIdentifierCodec());
    }

    @Test
    void noHeaderNoClaim_withDefaultOwnerSet_resolvesToIt() throws Exception {
        ReverseProxyServer.setVirtualHosts(OWNER, VHOST_YAML);
        ReverseProxyServer.setDefaultOwner(OWNER);

        VirtualHosts hosts = ReverseProxyServer.getVirtualHosts("203.0.113.5", null);

        assertThat(hosts).containsKey("app.test");
    }

    @Test
    void noHeaderNoClaim_withNoDefaultOwner_isNotFound() {
        ReverseProxyServer.setDefaultOwner(null);

        assertThatThrownBy(() -> ReverseProxyServer.getVirtualHosts("203.0.113.5", null))
                .isInstanceOf(NotFoundProxyVirtualHostsException.class);
    }

    @Test
    void invalidHeader_stillRejected_evenWithDefaultOwnerSet() throws Exception {
        ReverseProxyServer.setVirtualHosts(OWNER, VHOST_YAML);
        ReverseProxyServer.setDefaultOwner(OWNER);

        assertThatThrownBy(() -> ReverseProxyServer.getVirtualHosts("203.0.113.5", "not-a-valid-identifier"))
                .isInstanceOf(NotFoundProxyVirtualHostsException.class)
                .hasMessageContaining("Invalid " + ReverseProxyServer.DEFAULT_IDENTIFIER_HEADER + " header");
    }

    @Test
    void getDefaultOwner_reflectsWhatWasSet() {
        assertThat(ReverseProxyServer.getDefaultOwner()).isNull();

        ReverseProxyServer.setDefaultOwner(OWNER);

        assertThat(ReverseProxyServer.getDefaultOwner()).isEqualTo(OWNER);
    }

    @Test
    void setIdentifierHeaderName_changesTheInvalidHeaderMessage() throws Exception {
        ReverseProxyServer.setVirtualHosts(OWNER, VHOST_YAML);
        ReverseProxyServer.setIdentifierHeaderName("X-Custom-Identifier");

        assertThatThrownBy(() -> ReverseProxyServer.getVirtualHosts("203.0.113.5", "not-a-valid-identifier"))
                .isInstanceOf(NotFoundProxyVirtualHostsException.class)
                .hasMessageContaining("Invalid X-Custom-Identifier header");
    }

    @Test
    void setIdentifierCodec_isUsedToDecodeTheHeader() throws Exception {
        ReverseProxyServer.setVirtualHosts(OWNER, VHOST_YAML);
        // A custom codec that only accepts a fixed magic string as OWNER's identifier - proves
        // getVirtualHosts()/resolveIdentifier() go through the registered codec rather than any
        // built-in encoding.
        ReverseProxyServer.setIdentifierCodec(new IdentifierCodec() {
            @Override
            public String encode(long ownerId) {
                return "owner-" + ownerId;
            }

            @Override
            public Long decode(String value) {
                return "magic-token".equals(value) ? OWNER : null;
            }
        });

        assertThatThrownBy(() -> ReverseProxyServer.getVirtualHosts("203.0.113.5", "not-magic-token"))
                .isInstanceOf(NotFoundProxyVirtualHostsException.class)
                .hasMessageContaining("Invalid " + ReverseProxyServer.DEFAULT_IDENTIFIER_HEADER + " header");

        assertThat(ReverseProxyServer.resolveIdentifier("203.0.113.5", "magic-token")).isEqualTo("owner-0");
    }
}
