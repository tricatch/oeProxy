package tricatch.oe.proxy.spi;

/**
 * Encodes/decodes the owner identifier carried in the reverse proxy's identifier header (see
 * {@code ReverseProxyServer.getIdentifierHeaderName}) and used as the routing key for an owner's
 * virtual hosts. Registered via {@code ReverseProxyServer.setIdentifierCodec}.
 *
 * The library ships {@link PlainIdentifierCodec} as the default, which trusts the header value
 * as-is (no authentication). An embedding application that exposes this header to untrusted
 * clients across more than one owner should register its own codec that authenticates the value
 * (e.g. HMAC-signed), since a plain numeric id would let any client claim any owner's routes.
 */
public interface IdentifierCodec {

    /** The identifier string for an owner: the routing key, and the value clients send in the identifier header. */
    String encode(long ownerId);

    /** The owner id carried by an identifier value, or null if the value is not a valid identifier. Must not throw. */
    Long decode(String value);
}
