package tricatch.oe.proxy.pass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.proxy.exception.BadGatewayException;
import tricatch.oe.proxy.exception.GatewayTimeoutException;
import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.*;
import tricatch.oe.proxy.event.HttpEvent;
import tricatch.oe.proxy.event.HttpEventManager;
import tricatch.oe.proxy.event.HttpEventType;
import tricatch.oe.proxy.server.VThreadExecutor;
import tricatch.oe.proxy.server.VirtualPath;
import tricatch.oe.proxy.spi.ClientHints;
import tricatch.oe.proxy.util.HtmlUtil;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.locks.LockSupport;

public class PassResponseExecutor implements Stopable {

    private static final Logger logger = LoggerFactory.getLogger(PassResponseExecutor.class);

    private PassRequestExecutor passRequestExecutor;
    private Thread thisThread = null;

    private HttpStreamWriter clientOut = null;
    private HttpStreamReader serverIn = null;

    // The generation this executor was spawned for (see PassRequestExecutor.socketGeneration).
    // If a target change starts a newer generation while this one is still blocked/running, it
    // must not write anything (error or otherwise) to the shared clientOut anymore.
    private final long socketGeneration;

    private String rid;

    public PassResponseExecutor(PassRequestExecutor passRequestExecutor, HttpStreamReader serverIn, HttpStreamWriter clientOut, long socketGeneration){
        this.passRequestExecutor = passRequestExecutor;
        this.serverIn = serverIn;
        this.clientOut = clientOut;
        this.socketGeneration = socketGeneration;
        this.rid = passRequestExecutor.getUid();
    }

    private boolean isCurrentGeneration() {
        return passRequestExecutor.getSocketGeneration() == this.socketGeneration;
    }

    @Override
    public void run() {

        int bytesRead;
        boolean responseHeaderSent = false;

        try {

            this.thisThread = Thread.currentThread();

            if( logger.isDebugEnabled() ){
                logger.debug( "{}, vtStart", this.passRequestExecutor.getUid() );
            }

            while(true) {

                responseHeaderSent = false;

                // Fresh buffer per response: queued HttpEvent serializes async; reusing one HeaderLines lets the next readHeaders(clear) wipe it first.
                HeaderLines responseHeaders = new HeaderLines(HTTP.INIT_HEADER_LINES);

                //read-res-header
                bytesRead = serverIn.readHeaders(responseHeaders, HTTP.MAX_HEADER_LENGTH);

                this.rid = this.passRequestExecutor.getRid();

                if (bytesRead == -1) {
                    logger.warn("{}, No headers received from server"
                            , rid
                    );
                    writeBadGatewayIfPossible(null);
                    return;
                }

                //parse-res-header
                boolean isHeadRequest = "HEAD".equalsIgnoreCase(passRequestExecutor.getCurrentMethod());
                HttpResponse response = responseHeaders.parseHttpResponse(isHeadRequest);
                if (logger.isDebugEnabled()) {
                    logger.debug("{}, {}, Response Headers\n{}"
                            , rid
                            , HttpStream.Flow.RES
                            , responseHeaders
                    );
                    logger.debug("{}, {}, Response: {} {} {} (Body: {}, Connection: {}, ContentLength: {})"
                            , rid
                            , HttpStream.Flow.RES
                            , response.getVersion()
                            , response.getStatusCode()
                            , response.getStatusMessage()
                            , response.getBodyStream()
                            , response.getConnection()
                            , response.getContentLength()
                    );
                }

                // Enqueue RES header HttpEvent — tagged by resolved owner identifier, not raw
                // client IP, so two accounts sharing an egress IP can't see each other's live
                // traffic in the monitor (see ReverseProxyServer.resolveIdentifier()). Skipped
                // entirely when no monitor tab is watching this owner, same as the body relay
                // classes.
                String ownerIdentifier = this.passRequestExecutor.getOwnerIdentifier();
                boolean monitored = this.passRequestExecutor.isMonitored();
                if (monitored) {
                    HttpEvent resHeaderEvent = new HttpEvent(ownerIdentifier, this.rid, HttpEventType.RES_HEADER);
                    // Snapshot copy: this thread keeps using the live headers (writeHeaders below).
                    resHeaderEvent.setHeaders(responseHeaders.copy());
                    resHeaderEvent.setHttpStream(response.getBodyStream());
                    HttpEventManager.getInstance().enqueue(resHeaderEvent);
                }

                //write-res-header
                if (!isCurrentGeneration()) {
                    // A target change already spawned a newer PassResponseExecutor for this
                    // connection; writing this (now-orphaned) response would race that one's
                    // writes on the shared clientOut. Abandon quietly instead.
                    if (logger.isDebugEnabled()) {
                        logger.debug("{}, superseded by a newer target change; discarding response", rid);
                    }
                    return;
                }

                if (HttpStream.WEBSOCKET != response.getBodyStream()) {
                    // Undo the eager timeout widening PassRequestExecutor applies as soon as it
                    // merely sees an Upgrade: websocket request header, since this response shows
                    // the upgrade wasn't actually confirmed.
                    passRequestExecutor.restoreConfiguredSoTimeout();
                }

                clientOut.writeHeaders(responseHeaders);
                responseHeaderSent = true;

                // Relay response body to client
                HttpStream.Connection connection = RelayBody.relayResponseBody(ownerIdentifier, rid, HttpStream.Flow.RES, response, serverIn, clientOut, monitored);
                if (connection == HttpStream.Connection.CLOSE) {
                    passRequestExecutor.setStop(true);
                }

                if( passRequestExecutor.isStop()
                    || response.shouldCloseConnection()
                ){
                    break;
                }

                LockSupport.unpark(this.passRequestExecutor.getThread());
            }

        } catch (SocketTimeoutException e) {
            logger.error(this.passRequestExecutor.getUid() + ", " + e.getMessage());
            if (!responseHeaderSent) writeGatewayTimeoutIfPossible();
        } catch (SocketException e){
            if( "Connection reset".equals(e.getMessage())
                || "Socket closed".equals(e.getMessage())
            ) {
                logger.error(this.passRequestExecutor.getUid() + ", " + e.getMessage());
            } else {
                logger.error( this.passRequestExecutor.getUid() + ", " + e.getMessage(), e);
            }
            if (!responseHeaderSent) writeBadGatewayIfPossible(e);
        } catch (IOException e) {
            logger.error( this.passRequestExecutor.getUid() + ", " + e.getMessage(), e);
            if (!responseHeaderSent) writeBadGatewayIfPossible(e);
        } catch (IllegalArgumentException e) {
            // Malformed response line, or ambiguous Content-Length/Transfer-Encoding framing
            // rejected by HeaderLines.validateFraming() to prevent response smuggling.
            logger.warn("{}, Rejected malformed/ambiguous response: {}", this.passRequestExecutor.getUid(), e.getMessage());
            if (!responseHeaderSent) writeBadGatewayIfPossible(e);
        } finally {

            VThreadExecutor.removeVirtualThread(Thread.currentThread());

            if( logger.isDebugEnabled() ){
                logger.debug( "{}, vtEnd", this.passRequestExecutor.getUid() );
            }
            if (passRequestExecutor.getChildThread() == this.thisThread) {
                passRequestExecutor.setStop(true);
                LockSupport.unpark(this.passRequestExecutor.getThread());
            }
        }

    }

    // Called when the upstream connection fails/times out before any response headers were
    // forwarded to the client for the in-flight request, so the client would otherwise be left
    // waiting forever (see PassRequestExecutor's park loop, which only reads the client's next
    // request after this thread has already given up reading further responses).
    private void writeGatewayTimeoutIfPossible() {
        if (!isCurrentGeneration()) return;
        VirtualPath vp = passRequestExecutor.getCurrentVirtualPath();
        if (clientOut == null || vp == null || !passRequestExecutor.claimErrorResponse()) return;
        try {
            GatewayTimeoutException ex = new GatewayTimeoutException(
                    this.rid, passRequestExecutor.getCurrentHost(), vp.getTarget(), vp.getPath());
            HtmlUtil.writeGatewayTimeoutResponse(clientOut, ex, passRequestExecutor.getClientHints());
        } catch (IOException io) {
            // Best-effort courtesy write to a client that may already be gone (e.g. it gave up
            // and disconnected around the same time the upstream timed out) - the stack trace
            // adds nothing since there's no one left to receive the response either way.
            logger.error("{}, Failed to write 504 response: {}", this.rid, io.getMessage());
        }
    }

    private void writeBadGatewayIfPossible(Exception cause) {
        if (!isCurrentGeneration()) return;
        VirtualPath vp = passRequestExecutor.getCurrentVirtualPath();
        if (clientOut == null || vp == null || !passRequestExecutor.claimErrorResponse()) return;
        try {
            IOException ioCause = cause instanceof IOException ? (IOException) cause : null;
            BadGatewayException ex = new BadGatewayException(
                    this.rid, passRequestExecutor.getCurrentHost(), vp.getTarget(), vp.getPath(), ioCause);
            HtmlUtil.writeBadGatewayResponse(clientOut, ex, passRequestExecutor.getClientHints());
        } catch (IOException io) {
            // Same best-effort rationale as writeGatewayTimeoutIfPossible() above.
            logger.error("{}, Failed to write 502 response: {}", this.rid, io.getMessage());
        }
    }

    @Override
    public void stop() {

    }

    @Override
    public String getName() {
        if( this.thisThread==null ) return null;
        return thisThread.getName();
    }
}
