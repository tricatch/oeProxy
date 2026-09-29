package tricatch.oe.proxy.standalone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.exception.ConfigException;
import tricatch.oe.proxy.event.HttpEventManager;
import tricatch.oe.proxy.server.VirtualHosts;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Wires a parsed StandaloneOptions into ReverseProxyServer and blocks until the process is
 * killed. Standalone is a single-owner deployment: STANDALONE_OWNER is the only identifier ever
 * registered, and ReverseProxyServer.setDefaultOwner makes every request resolve to it even
 * though no embedding application ever sends an identifier header. The default
 * PlainIdentifierCodec (owner id as its own decimal string) is used as-is - a single-owner
 * deployment has no other owner an unauthenticated identifier value could be confused with.
 */
public class StandaloneServer {

    private static final Logger logger = LoggerFactory.getLogger(StandaloneServer.class);

    /** The single owner id used for every route in standalone mode - see ReverseProxyServer.setDefaultOwner. */
    public static final long STANDALONE_OWNER = 0L;

    public void start(StandaloneOptions options) throws OeProxyConfigException {

        Path caCert = options.getCaDir().resolve("ca.cer");
        Path caKey = options.getCaDir().resolve("ca.pfx");
        if (!Files.exists(caCert) || !Files.exists(caKey)) {
            throw new OeProxyConfigException(
                    "CA not found in " + options.getCaDir().toAbsolutePath() + " - run `oe-proxy ca` first");
        }

        // Must be set before setVirtualHosts(), which checks internal-only at apply time.
        ReverseProxyServer.setInternalOnlyUpstream(!options.isAllowExternalUpstream());
        ReverseProxyServer.setTrustInternalCertEnabled(true);

        // No setPort() here - ReverseProxyServer's static config already defaults the https
        // listener to HTTPS_PORT (443), and standalone always listens there.
        var https = ReverseProxyServer.getConfig().getHttps();
        https.setConnectTimeout(StandaloneOptions.CONNECT_TIMEOUT_MS);
        https.setReadTimeout(StandaloneOptions.READ_TIMEOUT_MS);

        // Unencrypted key (CaGenerator writes ca.pfx with no password) - matches the SSLUtil
        // read path's null/blank-priPwd branch.
        ReverseProxyServer.setCaFiles(caCert, caKey, null);

        try {
            ReverseProxyServer.setVirtualHosts(STANDALONE_OWNER, options.getRoutesYaml());
        } catch (Exception e) {
            throw new OeProxyConfigException("invalid routes: " + e.getMessage(), e);
        }
        ReverseProxyServer.setDefaultOwner(STANDALONE_OWNER);

        if (options.getMonitorLevel() != MonitorLevel.OFF) {
            // Same identifier the proxy resolves every request to (default owner, codec-encoded).
            HttpEventManager.getInstance().addEventConsumer(
                    new ConsoleMonitorConsumer(ReverseProxyServer.resolveIdentifier(null, null), "console", System.out, options.getMonitorLevel()));
            logger.info("monitor ({}): printing requests to stdout", options.getMonitorLevel().name().toLowerCase(java.util.Locale.ROOT));
        }

        // SSLPassServer runs on an executor thread (see startSslPassServer()), so a bind failure
        // there only gets logged, never thrown back to this caller - probe the port ourselves
        // first so an occupied/privileged port is reported as the one-line startup error the CLI
        // contract requires, not silence.
        probePort(ReverseProxyServer.HTTPS_PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> logger.info("stopping")));

        try {
            ReverseProxyServer.startSslPassServer();
        } catch (ConfigException e) {
            throw new OeProxyConfigException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OeProxyConfigException("interrupted while starting", e);
        }

        logRoutes();
        logger.info("listening on https://0.0.0.0:{}", ReverseProxyServer.HTTPS_PORT);

        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void logRoutes() {
        VirtualHosts hosts;
        try {
            // clientIp/identifierHeader are both null: with STANDALONE_OWNER set as the default
            // owner, resolveIdentifier() falls back to it regardless, so this returns exactly the
            // table just applied above.
            hosts = ReverseProxyServer.getVirtualHosts(null, null);
        } catch (Exception e) {
            logger.warn("could not read back routes for logging: {}", e.getMessage());
            return;
        }
        for (var entry : hosts.entrySet()) {
            var targets = entry.getValue().stream()
                    .map(p -> p.getTarget().toString())
                    .distinct()
                    .toList();
            logger.info("{} -> {}", entry.getKey(), targets);
        }
    }

    private void probePort(int port) throws OeProxyConfigException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
        } catch (BindException e) {
            throw new OeProxyConfigException("port " + port + " is not available: " + e.getMessage() + permissionHint(e), e);
        } catch (IOException e) {
            throw new OeProxyConfigException("port " + port + " is not available: " + e.getMessage(), e);
        }
    }

    // java.net reports a privileged-port bind refusal as a BindException whose message contains
    // "Permission denied" (Linux/macOS) - detect that case only, so the hint never fires for an
    // ordinary "Address already in use".
    private String permissionHint(BindException e) {
        String message = e.getMessage();
        if (message != null && message.toLowerCase().contains("permission denied")) {
            return " (ports below 1024 may need administrator/root privileges)";
        }
        return "";
    }
}
