package tricatch.oe.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import tricatch.oe.proxy.cfg.Config;
import tricatch.oe.proxy.cfg.VirtualHost;
import tricatch.oe.proxy.exception.ConfigException;
import tricatch.oe.proxy.exception.NotFoundProxyVirtualHostsException;
import tricatch.oe.proxy.server.VirtualHosts;
import tricatch.oe.proxy.spi.ErrorPageRenderer;
import tricatch.oe.proxy.spi.IdentifierCodec;
import tricatch.oe.proxy.spi.PlainIdentifierCodec;
import tricatch.oe.proxy.spi.VirtualHostsLoader;
import tricatch.oe.proxy.util.SocketUtils;
import tricatch.oe.proxy.exception.NotReadyCaException;
import tricatch.oe.proxy.server.*;
import tricatch.oe.proxy.util.ClaimedIpRegistry;
import tricatch.oe.proxy.util.SSLUtil;
import tricatch.oe.proxy.util.VirtualHostUtil;

import javax.net.ssl.SSLContext;
import java.io.*;
import java.net.MalformedURLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class ReverseProxyServer {

    private static final Logger logger = LoggerFactory.getLogger(ReverseProxyServer.class);

    private static final ThreadPoolExecutor serverExecutor = (ThreadPoolExecutor) Executors.newCachedThreadPool();

    // Routes are keyed by owner identifier (an opaque, embedding-application-defined encoding of
    // its numeric user id — see IdentifierCodec), not by client IP. IP-based fallback
    // identification now lives in ClaimedIpRegistry (an explicit, time-bounded claim - see
    // resolveIdentifier()) rather than an unbounded map here.
    private static final ConcurrentHashMap<String, VirtualHosts> identifierVirtualHostsMap = new ConcurrentHashMap<>();

    // Invoked to lazily rebuild an owner's routing table when it isn't cached (e.g. right after
    // process start) - see reloadVirtualHosts(). Null (the default) until the embedding
    // application registers one; a lookup simply reports "not found" until then.
    private static volatile VirtualHostsLoader virtualHostsLoader = null;

    // Lets the embedding application render its own branded error pages in place of this
    // library's built-in plain-HTML fallback - see HtmlUtil.
    private static volatile ErrorPageRenderer errorPageRenderer = null;

    // Identifier resolution (a request header the embedding application chooses) is always
    // available; IP-based identification is an opt-in fallback the embedding application can
    // enable for requests without that header. Off by default because client IP is often
    // shared/NATed and unreliable.
    private static volatile boolean ipIdentifierEnabled = false;

    /** The header name used when no embedding application has called {@link #setIdentifierHeaderName}. */
    public static final String DEFAULT_IDENTIFIER_HEADER = "X-Oe-Identifier";

    // The wire-level header name the embedding application sends the request owner's identifier
    // in. Configurable because different embedding applications may already have their own
    // established header name (see PassRequestExecutor, which reads this header at request time).
    private static volatile String identifierHeaderName = DEFAULT_IDENTIFIER_HEADER;

    public static String getIdentifierHeaderName() {
        return identifierHeaderName;
    }

    /** Sets the identifier header name the embedding application sends; must be non-null and non-blank. */
    public static void setIdentifierHeaderName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Identifier header name must not be null or blank");
        }
        identifierHeaderName = name.trim();
    }

    // Encodes/decodes the owner identifier carried in the identifier header and used as the
    // routing key in identifierVirtualHostsMap. Defaults to a plain, unauthenticated codec (fine
    // for single-owner/standalone deployments); a multi-owner embedding application that exposes
    // this header to untrusted clients should register its own authenticated codec (e.g.
    // HMAC-signed) via setIdentifierCodec.
    private static volatile IdentifierCodec identifierCodec = new PlainIdentifierCodec();

    public static IdentifierCodec getIdentifierCodec() {
        return identifierCodec;
    }

    /** Sets the codec used to encode/decode the owner identifier; must be non-null. */
    public static void setIdentifierCodec(IdentifierCodec codec) {
        if (codec == null) {
            throw new IllegalArgumentException("Identifier codec must not be null");
        }
        identifierCodec = codec;
    }

    // When an https backend's certificate fails validation, the client has no way to tell that's
    // the cause - PassRequestExecutor only ever surfaces a generic BadGatewayException. Internal
    // dev backends commonly use ad-hoc self-signed certs with no common CA to import, so this is
    // on by default; SocketUtils.isPrivateNetworkAddress still confines the bypass to
    // private/loopback/link-local backends even while enabled.
    private static volatile boolean trustInternalCertEnabled = true;

    // Where a virtual host's backend may live. On (the default): only internal-network addresses
    // (loopback, private, link-local), so a caller cannot use the reverse proxy to reach
    // arbitrary hosts from the server. Off: no restriction. Enforced when a config is applied
    // (setVirtualHosts, for backends that resolve right now) and again when the proxy connects
    // (SocketUtils), which is the check that cannot be dodged by a name that resolves later.
    private static volatile boolean internalOnlyUpstream = true;

    // An explicit opt-in for a single-owner embedding (the standalone CLI - see
    // tricatch.oe.proxy.standalone.StandaloneServer): when set, resolveIdentifier() falls back to
    // this owner for a request with no identifier header and no IP claim, instead of leaving the
    // request unidentified. Null (the default) leaves resolveIdentifier()'s behavior exactly as
    // before - the multi-owner embedding case never calls setDefaultOwner, so its requests are
    // unaffected.
    private static volatile Long defaultOwner = null;

    public static Long getDefaultOwner() {
        return defaultOwner;
    }

    /** Opts into single-owner fallback resolution in resolveIdentifier() - see the field's own comment. */
    public static void setDefaultOwner(Long userNo) {
        defaultOwner = userNo;
    }

    public static boolean isIpIdentifierEnabled() {
        return ipIdentifierEnabled;
    }

    public static void setIpIdentifierEnabled(boolean enabled) {
        ipIdentifierEnabled = enabled;
    }

    public static boolean isTrustInternalCertEnabled() {
        return trustInternalCertEnabled;
    }

    public static void setTrustInternalCertEnabled(boolean enabled) {
        trustInternalCertEnabled = enabled;
    }

    public static boolean isInternalOnlyUpstream() {
        return internalOnlyUpstream;
    }

    public static void setInternalOnlyUpstream(boolean enabled) {
        internalOnlyUpstream = enabled;
    }

    /** Registers the SPI used to lazily rebuild an owner's routing table - see reloadVirtualHosts(). */
    public static void setVirtualHostsLoader(VirtualHostsLoader loader) {
        virtualHostsLoader = loader;
    }

    /** Registers the embedding application's branded error-page renderer - see HtmlUtil. */
    public static void setErrorPageRenderer(ErrorPageRenderer renderer) {
        errorPageRenderer = renderer;
    }

    public static ErrorPageRenderer getErrorPageRenderer() {
        return errorPageRenderer;
    }

    /**
     * Rejects a config whose backend resolves, right now, to an address outside the internal
     * network while that restriction is on. A name that does not resolve yet is let through (a
     * dev backend that is simply down must still be saveable); the connect-time check in
     * SocketUtils covers it once it does resolve.
     */
    static void requireInternalUpstreams(VirtualHosts virtualHosts, boolean internalOnly) {
        if (!internalOnly) return;
        var checked = new java.util.HashSet<String>();
        for (var entry : virtualHosts.entrySet()) {
            for (var path : entry.getValue()) {
                var host = path.getTarget().getHost();
                if (!checked.add(host)) continue;
                java.net.InetAddress[] addresses;
                try {
                    addresses = java.net.InetAddress.getAllByName(host);
                } catch (java.net.UnknownHostException e) {
                    continue;
                }
                for (var address : addresses) {
                    if (!SocketUtils.isInternalAddress(address)) {
                        throw new tricatch.oe.proxy.exception.UpstreamNotInternalException("domain '" + entry.getKey() + "': backend '" + host
                                + "' resolves to " + address.getHostAddress()
                                + ", which is not an internal-network address");
                    }
                }
            }
        }
    }

    // Exposed so ForwardProxyServer can recognize a ${PROXY_SVR} self-loop connection (its own
    // outbound connection back into this reverse proxy) without hardcoding the port a second time.
    public static final int HTTPS_PORT = 443;

    private static Config config = null;
    private static SSLContext sslContext = null;
    private static SSLPassServer sslPassServer = null;

    static {

        config = new Config();

        config.getHttps().setPort(HTTPS_PORT);
        config.getHttps().setConnectTimeout(3000);
        config.getHttps().setReadTimeout(30000);

        config.getConsole().setPort(36900);
        config.getConsole().setConnectTimeout(3000);
        config.getConsole().setReadTimeout(30000);
    }

    /** Sets the CA cert/private-key files startSslPassServer() will load. Must be called before it. */
    public static void setCaFiles(java.nio.file.Path cert, java.nio.file.Path priKey, String priPwd) {
        config.getCa().setCert(cert == null ? null : cert.toString());
        config.getCa().setPriKey(priKey == null ? null : priKey.toString());
        config.getCa().setPriPwd(priPwd);
    }

    private static void initSslContext() throws ConfigException {

        var ca = config.getCa();

        if (ca.getCert() == null || ca.getPriKey() == null) {
            throw new NotReadyCaException("CA files not configured - call ReverseProxyServer.setCaFiles() first");
        }

        var certPath = java.nio.file.Path.of(ca.getCert());
        var keyPath  = java.nio.file.Path.of(ca.getPriKey());

        if (!java.nio.file.Files.exists(certPath) || !java.nio.file.Files.exists(keyPath)) {
            throw new NotReadyCaException("CA files not found: " + certPath + ", " + keyPath);
        }

        try {
            if (sslContext != null) {
                logger.info("Clearing existing SSL context");
            }
            sslContext = SSLUtil.initializeSSLContext(config);
            logger.info("SSL context initialized successfully");
        } catch (NotReadyCaException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to initialize SSL context", e);
            sslContext = null;
            throw e;
        }
    }

    public static synchronized void startSslPassServer() throws ConfigException, InterruptedException {

        initSslContext();

        if( sslPassServer!=null ){

            logger.info("Restart {}", SSLPassServer.class.getSimpleName());

            VThreadExecutor.stopAll();
            sslPassServer.stop();

            for(int i=0;i<10;i++){
                Thread.sleep(1000);
                RunState runState = sslPassServer.getRunState();
                if( RunState.STOPPED == runState ){
                    break;
                }
            }

        } else {

            logger.info("Start {}", SSLPassServer.class.getSimpleName());
        }

        sslPassServer = new SSLPassServer();
        serverExecutor.execute(sslPassServer);
    }


    public static void setVirtualHosts(Long userNo, String virtualHostsConfigYaml) throws MalformedURLException {

        Representer representer = new Representer(new DumperOptions());
        representer.getPropertyUtils().setSkipMissingProperties(true);
        LoaderOptions loaderOptions = new LoaderOptions();
        // virtualHostsConfigYaml is attacker-controllable (any authenticated user's own vhost
        // content). Plain Constructor honors an explicit YAML tag (e.g. "!!javax.script.ScriptEngineManager")
        // on ANY node regardless of the field's declared Java type, and merely constructing that
        // node can have side effects — a well-known SnakeYAML RCE gadget class. Reject every
        // explicit tag; legitimate vhost YAML never needs one (types are resolved implicitly via
        // the VirtualHost/VirtualDomain/VirtualLocation JavaBean shape).
        loaderOptions.setTagInspector(tag -> false);

        Constructor constructorVirtualHost = new Constructor(VirtualHost.class, loaderOptions);
        Yaml yamlVirtualHost = new Yaml(constructorVirtualHost, representer);

        VirtualHost virtualHost = yamlVirtualHost.load(virtualHostsConfigYaml);

        String identifier = identifierCodec.encode(userNo);
        var converted = VirtualHostUtil.convert(virtualHost.getVirtual());
        requireInternalUpstreams(converted, internalOnlyUpstream);
        identifierVirtualHostsMap.put(identifier, converted);
    }

    // Drops only the routing table. An IP claim is a deliberate, user-initiated action (see
    // ClaimedIpRegistry.claim()/apiTakeIp) and must not be cleared as a side effect of unrelated
    // vhost changes - it is released only by that same explicit action, either when the owner
    // claims a different IP (claim() releases their own prior claim first) or when another account
    // claims this same IP (claim()'s put() naturally overwrites the single owner a claimed IP maps
    // to). See invalidateVirtualHosts() below for the analogous no-side-effect table clear.
    public static void clearVirtualHosts(Long userNo) {
        identifierVirtualHostsMap.remove(identifierCodec.encode(userNo));
    }

    /**
     * Drops everything the reverse proxy holds for a removed account: its live routing table and
     * any "Use This IP" claim. Without this a deleted member's routes keep serving (their own
     * identifier, or their claimed IP, still resolves) until the process restarts.
     */
    public static void forgetUser(Long userNo) {
        String identifier = identifierCodec.encode(userNo);
        identifierVirtualHostsMap.remove(identifier);
        ClaimedIpRegistry.release(identifier);
    }

    // Drops only the cached routing table, leaving any IP claim intact — for invalidating an
    // owner who isn't the current request (e.g. a collaborator on a shared vhost someone else
    // just edited). Their next request finds no cached entry and lazily reloads via getVirtualHosts().
    public static void invalidateVirtualHosts(Long userNo) {
        identifierVirtualHostsMap.remove(identifierCodec.encode(userNo));
    }

    /** Claims clientIp for userNo in ClaimedIpRegistry - see ProxyController.apiTakeIp. */
    public static void claimIp(String clientIp, Long userNo) {
        ClaimedIpRegistry.claim(clientIp, identifierCodec.encode(userNo));
    }

    public static long claimedIpTtlHours() {
        return ClaimedIpRegistry.ttlHours();
    }

    /** The identifier currently holding clientIp in ClaimedIpRegistry, or null if unclaimed. */
    public static String getClaimedIdentifier(String clientIp) {
        return ClaimedIpRegistry.get(clientIp);
    }

    /**
     * Resolves the owner identifier for a request the same way getVirtualHosts() does — the
     * identifier header first, then the (togglable) IP-based fallback — without requiring a vhost
     * lookup to succeed. Used to tag each HttpEvent enqueued for a request/response with its true
     * owner, so the live traffic monitor (ProxyController.monitorEvent) can filter by account
     * instead of raw client IP: under NAT/CGNAT/shared egress, two different embedding-application
     * accounts can share one IP, and IP-only matching would let one account's monitor view see the
     * other's live traffic (headers, cookies, bodies). Returns null when ownership can't be
     * resolved — callers should simply not attribute/route the event rather than fail the request
     * over it.
     */
    public static String resolveIdentifier(String clientIp, String identifierHeader) {
        if (identifierHeader != null && !identifierHeader.isBlank()) {
            Long userNo = identifierCodec.decode(identifierHeader);
            return userNo == null ? null : identifierCodec.encode(userNo);
        }
        if (ipIdentifierEnabled) {
            String identifier = ClaimedIpRegistry.get(clientIp);
            if (identifier != null) return identifier;
        }
        // No identifier header and no claimed IP (see ClaimedIpRegistry / an explicit IP claim
        // made through the embedding application) - ownership genuinely can't be resolved from the
        // request alone. There is deliberately no loopback/sole-account shortcut here anymore: it
        // silently identified any 127.0.0.1 request as the one existing account, which broke down
        // the moment a second account was created and gave no visible signal that identification
        // was implicit rather than explicit. setDefaultOwner() below is the opposite of that: an
        // explicit opt-in the embedding application must call itself (the multi-owner embedding
        // case never does), not an implicit fallback baked into this method.
        if (defaultOwner != null) return identifierCodec.encode(defaultOwner);
        return null;
    }

    public static VirtualHosts getVirtualHosts(String clientIp, String identifierHeader) throws NotFoundProxyVirtualHostsException {
        // The identifier header takes precedence over the IP-based owner lookup,
        // since a shared/proxied client IP can otherwise resolve to the wrong owner.
        // The IP-based fallback itself can be turned off entirely via settings when
        // IP identification is untrustworthy (see ipIdentifierEnabled).
        if (identifierHeader != null && !identifierHeader.isBlank() && identifierCodec.decode(identifierHeader) == null) {
            throw new NotFoundProxyVirtualHostsException("Invalid " + getIdentifierHeaderName() + " header: " + identifierHeader);
        }
        String identifier = resolveIdentifier(clientIp, identifierHeader);
        if (identifier == null) throw new NotFoundProxyVirtualHostsException("No owner mapped for IP: " + clientIp);
        VirtualHosts virtualHosts = identifierVirtualHostsMap.get(identifier);
        if (virtualHosts == null) {
            virtualHosts = reloadVirtualHosts(clientIp, identifier);
        }
        if (virtualHosts == null) throw new NotFoundProxyVirtualHostsException("No virtual hosts configured for owner: " + identifier);
        return virtualHosts;
    }

    // A server restart clears identifierVirtualHostsMap, so the first request from an owner who
    // hasn't re-logged-in or re-saved yet would otherwise fail with NotFoundProxyVirtualHostsException.
    // Rebuild that owner's entry on demand via the registered VirtualHostsLoader instead.
    private static VirtualHosts reloadVirtualHosts(String clientIp, String identifier) {
        Long userNo = identifierCodec.decode(identifier);
        if (userNo == null || virtualHostsLoader == null) return null;
        try {
            var yaml = virtualHostsLoader.load(userNo, clientIp);
            if (yaml == null || yaml.isBlank()) return null;
            setVirtualHosts(userNo, yaml);
            return identifierVirtualHostsMap.get(identifier);
        } catch (Exception e) {
            logger.warn("Failed to lazily reload virtual hosts for owner {}: {}", identifier, e.getMessage());
            return null;
        }
    }

    public static Config getConfig(){
        return config;
    }
    public static SSLContext getSslContext(){
        return sslContext;
    }

    @SuppressWarnings("unchecked")
    public static String mergeVhostYaml(java.util.List<String> vhostContents) {
        var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        var allVirtual  = new java.util.ArrayList<>();
        var seenDomains = new java.util.LinkedHashSet<String>();

        for (var vhostContent : vhostContents) {
            if (vhostContent == null || vhostContent.isBlank()) continue;
            try {
                var parsed = (java.util.Map<String, Object>) yaml.load(vhostContent);
                if (parsed != null && parsed.get("virtual") instanceof java.util.List<?> virtual) {
                    for (var entry : virtual) {
                        if (entry instanceof java.util.Map<?, ?> map) {
                            var domain = (String) map.get("domain");
                            if (domain != null && seenDomains.contains(domain)) continue;
                            if (domain != null) seenDomains.add(domain);
                            allVirtual.add(entry);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (allVirtual.isEmpty()) return "";

        var opts = new DumperOptions();
        opts.setIndent(2);
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("virtual", allVirtual);
        return new Yaml(opts).dump(result);
    }

}
