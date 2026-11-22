package tricatch.oe.proxy.pass;

import tricatch.oe.proxy.server.VirtualHosts;

import java.util.Locale;

/** The forms a Host header value is used in: as a routing key, and as a TLS server name. */
final class HostNames {

    private static final String DEFAULT_PORT = ":443";

    private HostNames() {}

    /** Lower case, without the default port and the trailing dot: "A.com.:443" becomes "a.com". */
    static String canonical(String host) {
        if (host == null) return null;
        String name = host.toLowerCase(Locale.ROOT);
        if (name.endsWith(DEFAULT_PORT)) name = name.substring(0, name.length() - DEFAULT_PORT.length());
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        return name;
    }

    /** The name to send as SNI and to verify a certificate for: the host without any port. */
    static String serverName(String host) {
        if (host == null) return null;
        String name = host;
        int colon = name.lastIndexOf(':');
        if (name.startsWith("[")) {
            int end = name.indexOf(']');
            if (end > 0) name = name.substring(1, end);
        } else if (colon >= 0 && colon == name.indexOf(':')) {
            name = name.substring(0, colon);
        }
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }

    /**
     * The domain of {@code virtualHosts} that serves {@code host}, or null. An exact match wins, so
     * a table that names "a.com:443" or "A.com" itself keeps working as written.
     */
    static String vhostKey(VirtualHosts virtualHosts, String host) {
        if (host == null) return null;
        if (virtualHosts.containsKey(host)) return host;
        String name = canonical(host);
        if (virtualHosts.containsKey(name)) return name;
        for (String domain : virtualHosts.keySet()) {
            // Also keeps a vhost written in another case from falling through to the default route.
            if (name.equals(canonical(domain))) return domain;
        }
        return null;
    }
}
