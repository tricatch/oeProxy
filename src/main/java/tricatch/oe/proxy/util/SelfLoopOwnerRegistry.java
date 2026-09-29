package tricatch.oe.proxy.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

/**
 * Correlates a forward-proxy-authenticated user with the self-loop TCP connection the forward
 * proxy opens back into the embedding application's own reverse proxy when a ${PROXY_SVR} hosts
 * override routes a request there. Both proxies run in the same JVM, so instead of trying to
 * inject an identifier header into what is, for HTTPS, an opaque CONNECT tunnel, the forward proxy
 * registers the local (ephemeral) address:port it used to dial the reverse proxy, and the reverse
 * proxy looks itself up by the remote address of the socket it just accepted — by TCP definition,
 * the same address:port seen from either end of that one connection (see ForwardProxyServer's
 * ActivityTracker.serverConnected and PassRequestExecutor.run()).
 *
 * The registered IP is checked, not just the port: both the reverse proxy's port and the forward
 * proxy's port are bound on all interfaces (reachable from the LAN, not just loopback), and a
 * port number alone is a 16-bit space small enough for a network-adjacent, unauthenticated
 * attacker to brute-force. Two different hosts can independently pick the identical ephemeral
 * source port for their own, unrelated connections to this server — TCP's 4-tuple uniqueness is
 * enforced per source IP, not globally — so a bare port match would let an attacker's connection
 * be misattributed to whichever account's self-loop connection happens to be sitting in this
 * registry under the same port number. Requiring the accepted socket's actual peer IP to equal
 * the IP the forward proxy's own outbound connection used closes that gap: an attacker connecting
 * from anywhere other than this same self-loop path's real source address can never match,
 * regardless of which port number they guess or bind to.
 *
 * A registration is normally consumed within milliseconds (the reverse proxy reads it the instant
 * it accepts the connection); expireAfterWrite only bounds the rare case where a registered
 * connection never gets accepted (e.g. blocked/redirected before reaching the reverse proxy, or a
 * client that opens then abandons the tunnel), so an unconsumed entry doesn't linger forever.
 */
public class SelfLoopOwnerRegistry {

    private record Entry(String expectedIp, Long userNo) {}

    private static final Cache<Integer, Entry> PORT_TO_USER_NO = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(15))
            .maximumSize(1000)
            .build();

    private SelfLoopOwnerRegistry() {}

    public static void register(String localIp, int localPort, Long userNo) {
        if (localIp != null && userNo != null) PORT_TO_USER_NO.put(localPort, new Entry(localIp, userNo));
    }

    /**
     * Looks up and removes the registration for this port, if any — consume-on-read — but only
     * returns it when remoteIp matches the IP that was actually registered for that port. A
     * mismatch is treated as a plain miss (returns null, entry left in place for the real
     * connection to still claim) rather than an error, since an attacker probing port numbers
     * from an unrelated IP is expected to just fall through to the existing header/IP-claim
     * fallbacks like any other unidentified connection.
     */
    public static Long take(String remoteIp, int port) {
        if (remoteIp == null) return null;
        Entry entry = PORT_TO_USER_NO.getIfPresent(port);
        if (entry == null || !remoteIp.equals(entry.expectedIp())) return null;
        PORT_TO_USER_NO.asMap().remove(port, entry);
        return entry.userNo();
    }
}
