package tricatch.oe.proxy.util;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SocketUtilsTest {

    @Test
    void createHttps_closesTheTcpSocket_whenTlsHandshakeFails() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = serverSocket.getLocalPort();

            // createHttps() blocks in connect()/startHandshake(), so drive it from its own thread
            // while this thread plays a (non-TLS) server on the accepted connection.
            CompletableFuture<Exception> clientFailure = CompletableFuture.supplyAsync(() -> {
                try {
                    SocketUtils.createHttps("test-domain", "localhost", port, 3000, 3000);
                    return null;
                } catch (Exception e) {
                    return e;
                }
            });

            try (Socket accepted = serverSocket.accept()) {
                // Not a real TLS ServerHello, so the client's startHandshake() must fail parsing it.
                accepted.getOutputStream().write("not a tls server hello".getBytes(StandardCharsets.US_ASCII));
                accepted.getOutputStream().flush();

                Exception failure = clientFailure.get(5, TimeUnit.SECONDS);
                assertThat(failure).isNotNull();

                // The fix closes tcpSocket when the handshake fails; a real close() sends a TCP
                // FIN, which this side eventually observes as EOF (-1). Drain first: the client's
                // own ClientHello bytes are typically still sitting unread in this socket's receive
                // buffer at this point, so the very first read() can return real (non-EOF) bytes
                // even when the client has already closed — only the tail of the stream tells us.
                accepted.setSoTimeout(500);
                byte[] buf = new byte[256];
                boolean sawEof = false;
                long deadline = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        if (accepted.getInputStream().read(buf) == -1) {
                            sawEof = true;
                            break;
                        }
                    } catch (SocketTimeoutException ignored) {
                        // no more data arrived in this slice; keep polling until the deadline
                    }
                }
                assertThat(sawEof).as("client socket should have been closed (EOF observed)").isTrue();
            }
        }
    }
}
