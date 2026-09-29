package tricatch.oe.proxy.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.cfg.Config;
import tricatch.oe.proxy.cfg.attr.Https;
import tricatch.oe.proxy.pass.PassRequestExecutor;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.net.Socket;

public class SSLPassServer implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(SSLPassServer.class);

    private RunState runState = RunState.INIT;
    private boolean running = true;
    private SSLServerSocket sslSvrSocket = null;

    public SSLPassServer() {
    }

    public void stop(){
        this.running = false;
        if( this.sslSvrSocket!=null ) try{ this.sslSvrSocket.close(); }catch (Exception e){}
    }

    public RunState getRunState(){

        return this.runState;
    }

    public void run() {

        Config config = ReverseProxyServer.getConfig();
        String clazzName = this.getClass().getSimpleName();

        try {

            Thread.currentThread().setName("pt-https-pass");

            Https https = config.getHttps();

            logger.info("{} running...", clazzName);
            logger.info("{} port: {}", clazzName, https.getPort());
            logger.info("{} client.connect.timeout: {}", clazzName, https.getConnectTimeout() );
            logger.info("{} client.read.timeout: {}", clazzName, https.getReadTimeout() );

            SSLServerSocketFactory ssf = ReverseProxyServer.getSslContext().getServerSocketFactory();
            sslSvrSocket = (SSLServerSocket) ssf.createServerSocket(https.getPort());
            sslSvrSocket.setEnabledProtocols(new String[] { "TLSv1.2", "TLSv1.3" });

            this.running = true;
            this.runState = RunState.RUNNING;

            while (running) {

                // A transient accept() failure (e.g. a momentary "too many open files") must not
                // take down the whole listener — only stop() (which closes sslSvrSocket, turning
                // the next accept() into a expected "Socket closed") should end this loop.
                Socket socket;
                try {
                    socket = sslSvrSocket.accept();
                } catch (Exception e) {
                    if (!running || sslSvrSocket.isClosed()) break;
                    logger.error("errorSslPassServerAccept - " + e.getMessage(), e);
                    continue;
                }

                if (socket == null) continue;

                if (logger.isDebugEnabled()){
                    logger.debug("New client - h{}", socket.hashCode());
                }

                try {
                    socket.setSoTimeout(https.getReadTimeout());
                    VThreadExecutor.run(new PassRequestExecutor(socket, https.getConnectTimeout(), https.getReadTimeout()));
                } catch (Exception e) {
                    logger.error("errorSslPassServerDispatch - " + e.getMessage(), e);
                    try { socket.close(); } catch (Exception ce) {}
                }
            }

        } catch (Exception e) {
            if( "Socket closed".equals(e.getMessage()) ) logger.error( "sslPassServer socket closed");
            else logger.error("errorSslPassServer - " + e.getMessage(), e);
        } finally {
            if( sslSvrSocket!=null ) try{ sslSvrSocket.close(); } catch (Exception e){}
        }

        this.runState = RunState.STOPPED;
        logger.info("{} stopped...", clazzName);
    }
}
