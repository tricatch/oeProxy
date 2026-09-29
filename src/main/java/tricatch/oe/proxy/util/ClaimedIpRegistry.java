package tricatch.oe.proxy.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

/**
 * The IP-based fallback for reverse-proxy owner resolution when a request carries no
 * identifier header (see ReverseProxyServer.resolveIdentifier()). Replaces the old implicit
 * IP-to-owner map (which was written as a side effect of applying vhost config or of a lazy
 * cache-miss reload, and never expired) with an explicit, time-bounded claim: an owner only ends
 * up here by making an explicit IP claim through the embedding application (see
 * ReverseProxyServer.claimIp), so an entry always reflects a recent,
 * intentional choice rather than a stale side effect of some unrelated request.
 *
 * expireAfterWrite bounds how long a claim stays valid. Claiming a new IP for an owner releases
 * any previous claim for that same owner first, since only one IP can meaningfully be "current"
 * for routing purposes at a time - otherwise an owner who claims from home, then later from work,
 * would silently leave two IPs both routing to them indefinitely.
 */
public class ClaimedIpRegistry {

    private static final Duration TTL = Duration.ofHours(8);

    private static final Cache<String, String> IP_TO_IDENTIFIER = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .build();

    // Guards the release-then-put pair in claim() so it behaves as one atomic "move" operation.
    // Without this, two near-simultaneous claim() calls for the same identifier (e.g. a
    // double-click, or the same account claiming from two devices within milliseconds of each
    // other) could interleave as release()+release()+put(ipA)+put(ipB), leaving two IPs both
    // mapped to the same identifier - exactly the "two IPs both routing to them indefinitely" case
    // this class exists to prevent. Claims are a rare, explicit, low-frequency admin action (not a
    // hot request-path operation), so a plain lock costs nothing meaningful here.
    private static final Object CLAIM_LOCK = new Object();

    private ClaimedIpRegistry() {}

    public static void claim(String ip, String identifier) {
        if (ip == null || identifier == null) return;
        synchronized (CLAIM_LOCK) {
            release(identifier);
            IP_TO_IDENTIFIER.put(ip, identifier);
        }
    }

    /** Drops this owner's claim, whichever IP currently holds it (if any). */
    public static void release(String identifier) {
        if (identifier == null) return;
        synchronized (CLAIM_LOCK) {
            IP_TO_IDENTIFIER.asMap().values().removeIf(identifier::equals);
        }
    }

    public static String get(String ip) {
        return ip == null ? null : IP_TO_IDENTIFIER.getIfPresent(ip);
    }

    public static long ttlHours() {
        return TTL.toHours();
    }
}
