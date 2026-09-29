package tricatch.oe.proxy.spi;

/**
 * Lazily reloads a routing owner's virtual-host configuration when it isn't cached in memory
 * (e.g. right after process start). Registered by the embedding application via
 * {@code ReverseProxyServer.setVirtualHostsLoader}.
 */
@FunctionalInterface
public interface VirtualHostsLoader {

    /** Virtual-host YAML for userNo (already merged, with any placeholders substituted), or null/blank if none. clientIp is the requesting client's address. */
    String load(long userNo, String clientIp) throws Exception;
}
