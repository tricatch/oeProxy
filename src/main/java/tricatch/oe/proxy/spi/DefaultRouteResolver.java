package tricatch.oe.proxy.spi;

/**
 * Supplies a destination for a domain that has no virtual host, so the proxy can serve as the TLS
 * end of a forward proxy's CONNECT tunnel. Registered by the embedding application via
 * {@code ReverseProxyServer.setDefaultRouteResolver}; none is registered by default.
 *
 * <p>The resolver only chooses the destination. The library enforces the rest and it cannot be
 * turned off: the client must be on loopback, its Host must equal the SNI of its connection, the
 * destination must not be this proxy itself, and the upstream certificate is always verified
 * (the "trust internal certificates" setting does not apply).
 *
 * <p>{@code ReverseProxyServer.setInternalOnlyUpstream}, on by default, applies here as well:
 * switch it off to reach public sites. Anything that relays to the proxy from this machine counts
 * as a loopback client, so such a relay has to decide itself whom it lets in.
 */
@FunctionalInterface
public interface DefaultRouteResolver {

    /**
     * Called for every such request, so it should return quickly.
     *
     * @param host the requested domain in lower case, without a port
     * @return the destination, or null to leave the domain unhandled (404)
     */
    DefaultRoute resolve(String host);
}
