package tricatch.oe.proxy.spi;

/**
 * The default {@link IdentifierCodec}: the identifier is simply the owner id's decimal string
 * form, with no authentication. Fine for a single-owner (standalone) deployment or any setup
 * where the identifier header itself is trusted; a multi-owner deployment that exposes this
 * header to untrusted clients should register its own authenticated codec instead (e.g.
 * HMAC-signed) via {@code ReverseProxyServer.setIdentifierCodec}.
 */
public class PlainIdentifierCodec implements IdentifierCodec {

    @Override
    public String encode(long ownerId) {
        return Long.toString(ownerId);
    }

    @Override
    public Long decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
