package tricatch.oe.proxy.pass;

import io.github.azagniotov.matcher.AntPathMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.exception.NotFoundProxyVirtualHostsException;
import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.HttpStream;
import tricatch.oe.proxy.http.io.HeaderLines;
import tricatch.oe.proxy.http.io.HttpRequest;
import tricatch.oe.proxy.http.io.HttpStreamReader;
import tricatch.oe.proxy.http.io.HttpStreamWriter;
import tricatch.oe.proxy.event.HttpEvent;
import tricatch.oe.proxy.event.HttpEventManager;
import tricatch.oe.proxy.event.HttpEventType;
import tricatch.oe.proxy.server.VThreadExecutor;
import tricatch.oe.proxy.server.VirtualHosts;
import tricatch.oe.proxy.server.VirtualPath;
import tricatch.oe.proxy.exception.BadGatewayException;
import tricatch.oe.proxy.exception.NotFoundVhostException;
import tricatch.oe.proxy.spi.ClientHints;
import tricatch.oe.proxy.util.HtmlUtil;
import tricatch.oe.proxy.util.SelfLoopOwnerRegistry;
import tricatch.oe.proxy.util.SocketUtils;
import tricatch.oe.proxy.util.SysUtil;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public class PassRequestExecutor implements Stopable {

    private static final Logger logger = LoggerFactory.getLogger(PassRequestExecutor.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher.Builder().build();

    // WebSocket connections are long-lived and often sit idle between messages, so they get their
    // own (longer) socket timeout instead of the configurable connectTimeout/readTimeout used for
    // everything else. Not yet admin-configurable like those two — just named here for now so the
    // value isn't a duplicated magic number.
    private static final int WEBSOCKET_IDLE_TIMEOUT_MS = 1000 * 60 * 5;

    private Socket clientSocket;
    private HttpStreamReader clientIn = null;
    private HttpStreamWriter clientOut = null;

    private Socket serverSocket = null;
    private HttpStreamReader serverIn = null;
    private HttpStreamWriter serverOut = null;
    private VirtualPath preVirtualPath = null;

    // clientOut is handed to a spawned PassResponseExecutor as well, and a target change can
    // force-close the server socket it's blocked reading from, waking it into its own error path.
    // At most one HTTP response may reach the client per connection, so whichever thread (this
    // one or the child) hits an error first claims the write; the loser skips it rather than
    // racing unsynchronized writes onto the shared HttpStreamWriter.
    private final AtomicBoolean errorResponseClaimed = new AtomicBoolean(false);

    // Bumped every time a new server socket/child PassResponseExecutor replaces the previous one
    // (target change). A child captures the generation it was spawned for; if forceCloseServerSocket()
    // wakes it with an error after a newer generation has already started, it can tell it's been
    // superseded and must not write anything (error or otherwise) to the shared clientOut.
    private final AtomicLong socketGeneration = new AtomicLong(0);

    private final int connectTimeout;
    private final int readTimeout;

    private boolean stop = false;

    private Thread thisThread = null;
    private volatile Thread child = null;

    private final String uid = SysUtil.generateRequestId();
    private String rid = uid;
    private int reqCounter = 0;
    private VirtualHosts virtualHosts = null;
    private String clientId = null;
    private String ownerIdentifier = null;
    private ClientHints clientHints = ClientHints.NONE;
    private String currentHost = null;
    private String currentMethod = null;
    private String identifierHeader = null;

    // Decided once per request, right when the REQ_HEADER event is (or isn't) enqueued, and reused
    // as-is for RES_HEADER and for both body relays of this same request - so a monitor tab that
    // connects or disconnects mid-request can't split one request's events across "monitored" and
    // "not monitored", which would otherwise leave the frontend an orphaned RES_HEADER/body event
    // with no REQ_HEADER row to attach it to (see PassResponseExecutor.isMonitored()).
    private boolean monitored = false;

    // Resolved at most once per accepted connection (not per request - a keep-alive connection
    // can carry many requests) from SelfLoopOwnerRegistry, for a ${PROXY_SVR}/loopback forward-proxy
    // self-loop connection that carries no identifier header of its own (see SelfLoopOwnerRegistry).
    // Deliberately NOT looked up right at connection-accept time: ForwardProxyServer.
    // proxyToServerConnectionSucceeded() (which does the registration) races this connection's
    // own accept() on the reverse-proxy side, and consistently loses it in practice (registration
    // is a Netty-event-loop callback; accept() unblocks a waiting virtual thread directly). Looking
    // it up here instead - only once the first request's headers have actually been read - is
    // race-free by construction: the browser can't even start its TLS handshake (whose completion
    // readHeaders() below waits on) until after the CONNECT response, which LittleProxy sends only
    // once proxyToServerConnectionSucceeded() has already run and registered this port.
    private String selfLoopIdentifierHeader = null;
    private boolean selfLoopChecked = false;

    public PassRequestExecutor(Socket clientSocket, int connectTimeout, int readTimeout){

        this.clientSocket = clientSocket;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public void setStop(boolean stop){
        this.stop = stop;
    }

    public boolean isStop(){
        return this.stop;
    }

    public Thread getChildThread(){
        return this.child;
    }

    public String getUid(){
        return this.uid;
    }
    public String getRid(){
        return this.rid;
    }
    
    public String getClientId(){
        return this.clientId;
    }

    // Resolved owner identifier for the current/most-recent request on this connection (see
    // ReverseProxyServer.resolveIdentifier()) — used to tag HttpEvents with their true owner instead
    // of the raw client IP, so the live monitor can't cross-leak traffic between two accounts
    // sharing an egress IP. null until the first successful getVirtualHosts() call.
    public String getOwnerIdentifier(){
        return this.ownerIdentifier;
    }

    // Same monitored decision REQ_HEADER/the request body relay already used for this request -
    // see the field comment on `monitored` for why RES_HEADER/the response body relay must reuse
    // it rather than independently re-checking HttpEventManager.hasSubscriber().
    public boolean isMonitored(){
        return this.monitored;
    }

    public ClientHints getClientHints(){
        return this.clientHints;
    }

    public String getCurrentHost(){
        return this.currentHost;
    }

    public String getCurrentMethod(){
        return this.currentMethod;
    }

    public long getSocketGeneration(){
        return this.socketGeneration.get();
    }

    // Called by PassResponseExecutor once a response comes back that did NOT confirm a
    // WebSocket upgrade (status other than 101), to undo the eager timeout widening applied
    // in the request loop as soon as an Upgrade: websocket request header was merely seen —
    // otherwise a rejected upgrade would leave this keep-alive connection running with the
    // much longer websocket idle timeout for every later request.
    public void restoreConfiguredSoTimeout() throws SocketException {
        if (this.clientSocket != null) this.clientSocket.setSoTimeout(this.readTimeout);
        if (this.serverSocket != null) this.serverSocket.setSoTimeout(this.readTimeout);
    }

    public VirtualPath getCurrentVirtualPath(){
        return this.preVirtualPath;
    }

    public Thread getThread(){
        return this.thisThread;
    }

    /**
     * At most one caller may write the client-facing error response for this connection.
     * @return true if the caller won the claim and may write to clientOut; false if another
     *         thread already claimed it (the caller should skip writing).
     */
    public boolean claimErrorResponse(){
        return errorResponseClaimed.compareAndSet(false, true);
    }

    @Override
    public void run() {

        try {

            this.clientId = this.clientSocket.getInetAddress().getHostAddress();

            if( logger.isDebugEnabled() ){
                logger.debug( "{}, vtStart / {}", this.uid, this.clientId );
            }

            thisThread = Thread.currentThread();
            clientIn = new HttpStreamReader(clientSocket.getInputStream(), HTTP.BODY_BUFFER_SIZE);
            clientOut = new HttpStreamWriter(clientSocket.getOutputStream());

            while (true) {

                reqCounter++;
                this.rid = this.uid + '-' + reqCounter;

                //read-req-header
                HeaderLines requestHeaders = new HeaderLines(HTTP.INIT_HEADER_LINES);
                int bytesRead = clientIn.readHeaders(requestHeaders, HTTP.MAX_HEADER_LENGTH);

                if (bytesRead == -1) {
                    logger.warn("{}, No headers received from client"
                            , rid
                    );
                    return;
                }

                // client hints for the embedding application's error-page renderer (e.g. locale)
                String acceptLanguage = requestHeaders.getHeaderValueAsString(HTTP.HEADER.ACCEPT_LANGUAGE);
                String cookieHeader = requestHeaders.getHeaderValueAsString(HTTP.HEADER.COOKIE);
                this.clientHints = new ClientHints(cookieHeader, acceptLanguage);

                this.identifierHeader = requestHeaders.getHeaderValueAsString(
                        ReverseProxyServer.getIdentifierHeaderName().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                // An explicit header (e.g. the oeOID extension) still wins - the self-loop
                // registration only fills in for the common case where forward-proxied HTTPS
                // traffic carries no header at all (see SelfLoopOwnerRegistry and the field
                // comment on selfLoopIdentifierHeader for why this is looked up here, not at accept time).
                if ((this.identifierHeader == null || this.identifierHeader.isBlank()) && !this.selfLoopChecked) {
                    this.selfLoopChecked = true;
                    // this.clientId (the accepted socket's peer IP) must match the IP the
                    // forward-proxy's own self-loop connection actually used - see
                    // SelfLoopOwnerRegistry's javadoc for why a bare port match isn't safe on its
                    // own (both proxy ports are reachable from the LAN, not just loopback).
                    Long selfLoopUserNo = SelfLoopOwnerRegistry.take(this.clientId, this.clientSocket.getPort());
                    this.selfLoopIdentifierHeader = selfLoopUserNo != null ? ReverseProxyServer.getIdentifierCodec().encode(selfLoopUserNo) : null;
                    logger.debug("{}, self-loop registry lookup: ip={}, port={}, userNo={}", rid, this.clientId, this.clientSocket.getPort(), selfLoopUserNo);
                }
                if ((this.identifierHeader == null || this.identifierHeader.isBlank()) && this.selfLoopIdentifierHeader != null) {
                    this.identifierHeader = this.selfLoopIdentifierHeader;
                }
                this.virtualHosts = ReverseProxyServer.getVirtualHosts(this.clientId, this.identifierHeader);
                this.ownerIdentifier = ReverseProxyServer.resolveIdentifier(this.clientId, this.identifierHeader);

                // Whether any monitor tab is watching this owner — events are tagged by resolved
                // owner identifier, not raw client IP, so two accounts sharing an egress IP can't
                // see each other's live traffic in the monitor (see
                // ReverseProxyServer.resolveIdentifier()).
                this.monitored = HttpEventManager.getInstance().hasSubscriber(this.ownerIdentifier);

                // Parse HTTP request
                HttpRequest httpRequest = requestHeaders.parseHttpRequest();

                // Enqueue REQ header HttpEvent after parsing so it can carry the body stream type.
                // It holds a snapshot copy of the headers as received, since the live object is
                // rewritten by applyHeaderRules() below and shared with this proxy thread.
                // Skipped entirely when no monitor tab is watching, same as the body relay classes.
                if (this.monitored) {
                    HttpEvent reqHeaderEvent = new HttpEvent(this.ownerIdentifier, this.rid, HttpEventType.REQ_HEADER);
                    reqHeaderEvent.setHeaders(requestHeaders.copy());
                    reqHeaderEvent.setHttpStream(httpRequest.getHttpStream());
                    HttpEventManager.getInstance().enqueue(reqHeaderEvent);
                }
                this.currentHost = httpRequest.getHost();
                this.currentMethod = httpRequest.getMethod();

                if (logger.isDebugEnabled()) {
                    logger.debug("{}, {}, Request Headers\n{}"
                            , rid
                            , HttpStream.Flow.REQ
                            , requestHeaders
                    );
                    logger.debug("{}, {}, Request: {} {} {} (Host: {}, Body: {}, Connection: {}, ContentLength: {})"
                            , rid
                            , HttpStream.Flow.REQ
                            , httpRequest.getMethod()
                            , httpRequest.getPath()
                            , httpRequest.getVersion()
                            , httpRequest.getHost()
                            , httpRequest.getHttpStream()
                            , httpRequest.getConnection()
                            , httpRequest.getContentLength()
                    );
                }

                VirtualPath virtualPath = getVirtualPath(rid, httpRequest.getHost(), httpRequest.getPath());

                //browser > keep-alive > route > target server
                boolean targetChanged = preVirtualPath == null
                        || !preVirtualPath.getTarget().toString().equals(virtualPath.getTarget().toString());
                preVirtualPath = virtualPath;

                if (logger.isDebugEnabled()) {
                    logger.debug("{}, {}, targetChanged={}, targetServerSocket={}"
                            , rid
                            , HttpStream.Flow.REQ
                            , targetChanged
                            , serverSocket
                    );
                }

                //create socket - url matched
                if (serverSocket == null || targetChanged) {

                    // Bump the generation *before* tearing down the old socket, so a stale child
                    // woken by forceCloseServerSocket() below immediately observes itself as
                    // superseded via isCurrentGeneration() instead of racing the new socket's
                    // (possibly slow, up to connectTimeout) setup.
                    long myGeneration = socketGeneration.incrementAndGet();

                    //create new server socket - new target route
                    if (targetChanged && serverSocket != null) forceCloseServerSocket();

                    serverSocket = createServerSocket(rid, httpRequest.getHost(), virtualPath);
                    serverIn = new HttpStreamReader(serverSocket.getInputStream(), HTTP.BODY_BUFFER_SIZE);
                    serverOut = new HttpStreamWriter(serverSocket.getOutputStream());

                    String tName = Thread.currentThread().getName();
                    if( tName.endsWith("x0") ) tName = tName.substring(0, tName.length()-1) + reqCounter;

                    child =  VThreadExecutor.run(
                            new PassResponseExecutor(this, serverIn, clientOut, myGeneration)
                            , tName
                        );

                }

                // Apply configured per-location header add/remove rules before forwarding upstream.
                applyHeaderRules(requestHeaders, virtualPath);

                //write-req-header
                serverOut.writeHeaders(requestHeaders);

                if (HttpStream.WEBSOCKET == httpRequest.getHttpStream()) {
                    this.clientSocket.setSoTimeout(WEBSOCKET_IDLE_TIMEOUT_MS);
                    this.serverSocket.setSoTimeout(WEBSOCKET_IDLE_TIMEOUT_MS);
                }

                // Relay request body to server if exists
                HttpStream.Connection connection = RelayBody.relayRequestBody(this.ownerIdentifier, rid, HttpStream.Flow.REQ, httpRequest, clientIn, serverOut, this.monitored);
                if (connection == HttpStream.Connection.CLOSE) {
                    this.stop = true;
                }

                if (logger.isDebugEnabled()) {
                    logger.debug("{}, {}, stop={} or park"
                            , rid
                            , HttpStream.Flow.REQ
                            , this.stop
                    );
                }

                if (this.stop) {
                    break;
                }

                LockSupport.park();

                // PassResponseExecutor may have set stop=true (e.g. upstream read timeout) while
                // this thread was parked; honor it now instead of blocking on the next client read
                // with no one left to relay a response for the request already sent upstream.
                if (this.stop) {
                    break;
                }
            }
        } catch (BadGatewayException e) {
            logger.error("{}, {}, Upstream connection failed: {}"
                    , e.getRid()
                    , HttpStream.Flow.REQ
                    , e.getMessage()
                    , e
            );
            try {
                if (clientOut != null && claimErrorResponse()) {
                    HtmlUtil.writeBadGatewayResponse(clientOut, e, this.clientHints);
                }
            } catch (IOException io) {
                logger.error("{}, Failed to write 502 response: {}", uid, io.getMessage(), io);
            }
        } catch (SocketTimeoutException e){
            logger.error( uid + ", " + e.getMessage());
        } catch (SocketException e){
            if( "Connection reset".equals(e.getMessage())
                    || "Socket closed".equals(e.getMessage())
            ) {
                logger.error( uid + ", " + e.getMessage());
            } else {
                logger.error( uid + ", " + e.getMessage(), e);
            }
        } catch (NotFoundVhostException e) {
            logger.warn("{}, {}", uid, e.getMessage());
            try {
                if (clientOut != null && claimErrorResponse()) {
                    HtmlUtil.writeNotFoundVhostResponse(clientOut, e.getRequestHost(), e.getRequestPath(), this.clientHints);
                }
            } catch (IOException io) {
                logger.error("{}, Failed to write not-found-vhost response: {}", uid, io.getMessage(), io);
            }
        } catch (IOException e) {
            logger.error(uid + ", " + e.getMessage(), e);
        } catch (NotFoundProxyVirtualHostsException e) {
            logger.warn("{}, {}", uid, e.getMessage());
            try {
                if (clientOut != null && claimErrorResponse()) {
                    HtmlUtil.writeNoVhostsResponse(clientOut, this.clientId, this.identifierHeader, this.clientHints);
                }
            } catch (IOException io) {
                logger.error("{}, Failed to write no-vhosts response: {}", uid, io.getMessage(), io);
            }
        } catch (IllegalArgumentException e) {
            // Malformed request line, or ambiguous Content-Length/Transfer-Encoding framing
            // rejected by HeaderLines.validateFraming() to prevent request smuggling.
            logger.warn("{}, Rejected malformed/ambiguous request: {}", uid, e.getMessage());
            try {
                if (clientOut != null && claimErrorResponse()) {
                    HtmlUtil.writeBadRequestResponse(clientOut, e.getMessage());
                }
            } catch (IOException io) {
                logger.error("{}, Failed to write 400 response: {}", uid, io.getMessage(), io);
            }
        } finally {
            VThreadExecutor.removeVirtualThread(Thread.currentThread());
            this.stop = true;
            closeAll();
        }


    }

    private void closeAll(){

        if( logger.isDebugEnabled() ){
            logger.debug( "{}, vtEnd & closeSocket, vtRes={}", this.uid, child != null ? child.getName() : "none" );
        }

        closeQuietly(serverIn, "serverIn");
        closeQuietly(serverOut, "serverOut");
        closeQuietly(serverSocket, "serverSocket");

        closeQuietly(clientIn, "clientIn");
        closeQuietly(clientOut, "clientOut");
        closeQuietly(clientSocket, "clientSocket");

        serverIn = null;
        serverOut = null;
        serverSocket = null;

        clientIn = null;
        clientOut = null;
        clientSocket = null;
    }

    private void forceCloseServerSocket(){

        closeQuietly(serverIn, "previous serverIn");
        closeQuietly(serverOut, "previous serverOut");
        closeQuietly(serverSocket, "previous serverSocket");

        serverIn = null;
        serverOut = null;
        serverSocket = null;
    }

    private void closeQuietly(Closeable resource, String label) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception e) {
            logger.debug("Error closing {}: {}", label, e.getMessage());
        }
    }

    // Header names applyHeaderRules() must never let a per-location add/remove rule touch.
    // requestHeaders.parseHttpRequest() (called before applyHeaderRules(), see run()) already
    // read Content-Length/Transfer-Encoding off the ORIGINAL headers to run validateFraming()
    // and to decide httpRequest.getHttpStream() — the body-framing mode RelayBody.relayRequestBody()
    // then relays by. A location rule that added/removed one of these after that point would
    // desync what the rewritten headers *declare* to the backend from what body framing is
    // *actually* relayed, silently reintroducing the exact CL/TE ambiguity validateFraming()
    // exists to reject — this vhost config is attacker-controllable (any authenticated user's
    // own vhost, see ReverseProxyServer's YAML-loading comment), so this isn't just a
    // misconfiguration guard.
    private static final java.util.Set<String> HEADER_RULES_PROTECTED_NAMES = java.util.Set.of(
            "content-length", "transfer-encoding", "connection"
    );

    private void applyHeaderRules(HeaderLines requestHeaders, VirtualPath virtualPath) {
        List<String> removeHeader = virtualPath.getRemoveHeader();
        if (removeHeader != null) {
            for (String name : removeHeader) {
                if (name == null || HEADER_RULES_PROTECTED_NAMES.contains(name.trim().toLowerCase(Locale.ROOT))) continue;
                requestHeaders.removeHeadersNamed(name);
            }
        }
        List<String> addHeader = virtualPath.getAddHeader();
        if (addHeader != null) {
            for (String headerLine : addHeader) {
                if (headerLine == null) continue;
                int colon = headerLine.indexOf(':');
                if (colon <= 0) continue;
                if (HEADER_RULES_PROTECTED_NAMES.contains(headerLine.substring(0, colon).trim().toLowerCase(Locale.ROOT))) continue;
                requestHeaders.setHeaderLine(headerLine);
            }
        }
    }

    private VirtualPath getVirtualPath(String rid, String vhost, String uri) throws IOException {

        if( logger.isDebugEnabled() ){
            logger.debug( "{}, {}, Find virtual path, vhost={}, uri={}"
                    , rid
                    , HttpStream.Flow.REQ
                    , vhost
                    , uri
            );
        }

        List<VirtualPath> urls = virtualHosts.get(vhost);

        if( urls==null || urls.isEmpty()) throw new NotFoundVhostException(vhost, uri, "Undefined vhost - " + vhost);

        VirtualPath virtualPath = null;

        for (VirtualPath vu : urls) {
            boolean matched = PATH_MATCHER.isMatch(vu.getPath(), uri);

            if (matched) {
                virtualPath = vu;
                if (logger.isDebugEnabled()) {
                    logger.debug("{}, {}, Reserved path - {}, {}, {}, {}"
                            , rid
                            , HttpStream.Flow.REQ
                            , vhost
                            , vu.getPath()
                            , vu.getTarget()
                            , uri
                    );
                }
                break;
            }
        }

        if( virtualPath == null ) throw new NotFoundVhostException(vhost, uri, "Not found path - " + uri + " -- " + vhost);

        return virtualPath;
    }

    private Socket createServerSocket(String rid, String vhost, VirtualPath virtualPath) throws BadGatewayException {

        if( logger.isDebugEnabled() ){
            logger.debug( "{}, {}, Create socket, vhost={}, uri={}"
                    , rid
                    , HttpStream.Flow.REQ
                    , vhost
                    , virtualPath.getTarget()
            );
        }

        URL target = virtualPath.getTarget();

        try {
            if( "https".equals(target.getProtocol()) ){
                int port = target.getPort() <= 0 ? 443 : target.getPort();
                if( logger.isDebugEnabled() ){
                    logger.debug("{}, {}, Create HTTPS {}:{} / {}"
                            , rid
                            , HttpStream.Flow.REQ
                            , target.getHost()
                            , port
                            , vhost
                    );
                }
                return SocketUtils.createHttps(vhost, target.getHost(), port, this.connectTimeout, this.readTimeout, ReverseProxyServer.isTrustInternalCertEnabled(), ReverseProxyServer.isInternalOnlyUpstream());
            } else {
                int port = target.getPort() <= 0 ? 80 : target.getPort();
                if( logger.isDebugEnabled() ) logger.debug("{}, {}, Create HTTP {}:{} / {}"
                        , rid
                        , HttpStream.Flow.REQ
                        , target.getHost()
                        , port
                        , vhost
                );
                return SocketUtils.createHttp(target.getHost(), port, this.connectTimeout, this.readTimeout, ReverseProxyServer.isInternalOnlyUpstream());
            }
        } catch (IOException e) {
            throw new BadGatewayException(rid, vhost, target, virtualPath.getPath(), e);
        }
    }

    @Override
    public void stop() {
        this.closeAll();
    }

    @Override
    public String getName() {
        if( this.thisThread==null ) return null;
        return thisThread.getName();
    }
}
