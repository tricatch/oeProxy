package tricatch.oe.proxy.pass;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.event.HttpEvent;
import tricatch.oe.proxy.event.HttpEventConsumer;
import tricatch.oe.proxy.event.HttpEventManager;
import tricatch.oe.proxy.event.HttpEventType;

import io.github.tricatch.gotpache.cert.RootCertificateCreator;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static tricatch.oe.proxy.pass.RelayFixture.*;

// End-to-end behaviour of one client connection through the real TLS listener.
class PassRelayTest {

    private static final String HOST = "relay.test";

    @TempDir
    static Path dir;
    private static RelayFixture proxy;

    @BeforeAll
    static void start() throws Exception {
        proxy = new RelayFixture(dir, 30_000);
    }

    @AfterAll
    static void stop() throws Exception {
        proxy.close();
    }

    private static void routeAll(int port) throws Exception {
        route("""
                virtual:
                  - domain: %s
                    location:
                      - host: http://127.0.0.1:%d
                        path: [ /** ]
                """.formatted(HOST, port));
    }

    @Test
    void aTargetChangeOnAKeepAliveConnection_stillGetsItsResponse() throws Exception {
        int a = proxy.http(always("a"));
        // Answers late enough for the replaced response thread to have finished first.
        int b = proxy.http((in, out, socket) -> {
            while (!head(in).isEmpty()) {
                Thread.sleep(300);
                reply(out, "b");
            }
        });
        route("""
                virtual:
                  - domain: %s
                    location:
                      - host: http://127.0.0.1:%d
                        path: [ /a ]
                      - host: http://127.0.0.1:%d
                        path: [ /b ]
                """.formatted(HOST, a, b));

        try (Client client = proxy.client(HOST)) {
            assertThat(client.get(HOST, "/a").body()).isEqualTo("a");
            assertThat(client.get(HOST, "/b").body()).isEqualTo("b");
            assertThat(client.get(HOST, "/a").body()).isEqualTo("a");
        }
    }

    @Test
    void anUpstreamClosingAnIdleConnection_closesTheClientWithoutAnUnsolicitedResponse() throws Exception {
        routeAll(proxy.http((in, out, socket) -> {
            head(in);
            reply(out, "ok");
            Thread.sleep(300);
        }));

        try (Client client = proxy.client(HOST)) {
            assertThat(client.get(HOST, "/").body()).isEqualTo("ok");
            assertThat(client.in.read()).as("end of stream, not a 502 nobody asked for").isEqualTo(-1);
        }
    }

    @Test
    void interimResponses_areForwardedButDoNotAnswerTheRequest() throws Exception {
        routeAll(proxy.http((in, out, socket) -> {
            while (!head(in).isEmpty()) {
                out.write("HTTP/1.1 103 Early Hints\r\nLink: </a.css>; rel=preload\r\n\r\n".getBytes());
                out.flush();
                Thread.sleep(200);
                reply(out, "ok");
            }
        }));
        List<HttpEvent> events = new CopyOnWriteArrayList<>();
        HttpEventConsumer monitor = new HttpEventConsumer() {
            @Override
            public String getClientId() {
                return ReverseProxyServer.getIdentifierCodec().encode(OWNER);
            }

            @Override
            public String getChannelId() {
                return "pass-relay-test";
            }

            @Override
            public void process(HttpEvent event) {
                if (event.getType() == HttpEventType.RES_HEADER) events.add(event);
            }
        };
        HttpEventManager.getInstance().addEventConsumer(monitor);
        try (Client client = proxy.client(HOST)) {
            // Both requests are on the wire at once, so a request loop released by the 103 reads the second.
            String request = "GET / HTTP/1.1\r\nHost: " + HOST + "\r\n\r\n";
            client.send(request + request);
            for (int i = 0; i < 2; i++) {
                assertThat(client.read().status()).contains(" 103 ");
                assertThat(client.read().body()).isEqualTo("ok");
            }
            for (int i = 0; i < 50 && events.size() < 2; i++) Thread.sleep(100);
        } finally {
            HttpEventManager.getInstance().removeEventConsumer(monitor);
        }

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getHeaders().get(0).toString().contains(" 200 "));
        assertThat(events).extracting(e -> e.getRid().substring(e.getRid().lastIndexOf('-')))
                .containsExactlyInAnyOrder("-1", "-2");
    }

    @Test
    void continueIsForwarded_thenTheBodyAndTheFinalResponse() throws Exception {
        routeAll(proxy.http((in, out, socket) -> {
            for (String head; !(head = head(in)).isEmpty(); ) {
                if (head.contains("100-continue")) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes());
                    out.flush();
                }
                reply(out, new String(in.readNBytes(contentLength(head))));
            }
        }));

        try (Client client = proxy.client(HOST)) {
            client.send("POST / HTTP/1.1\r\nHost: " + HOST + "\r\nExpect: 100-continue\r\nContent-Length: 5\r\n\r\n");
            assertThat(client.read().status()).contains(" 100 ");
            client.send("hello");
            assertThat(client.read().body()).isEqualTo("hello");
            assertThat(client.get(HOST, "/").status()).contains(" 200 ");
        }
    }

    @Test
    void aResponseSentBeforeTheRequestBodyEnds_doesNotHangTheConnection() throws Exception {
        routeAll(proxy.http((in, out, socket) -> {
            for (String head; !(head = head(in)).isEmpty(); ) {
                reply(out, "early");
                in.readNBytes(contentLength(head));
            }
        }));

        try (Client client = proxy.client(HOST)) {
            client.send("POST / HTTP/1.1\r\nHost: " + HOST + "\r\nContent-Length: 4\r\n\r\n");
            assertThat(client.read().body()).isEqualTo("early");
            Thread.sleep(200); // the proxy is blocked reading the body when the response completes
            client.send("data");
            assertThat(client.get(HOST, "/").body()).isEqualTo("early");
        }
    }

    @Test
    void headerLinesUpTo64K_passInBothDirections() throws Exception {
        String big = "x".repeat(32 * 1024);
        routeAll(proxy.http((in, out, socket) -> {
            for (String head; !(head = head(in)).isEmpty(); ) {
                reply(out, "X-Big: " + big + "\r\n", head.contains(big) ? "received" : "missing");
            }
        }));

        try (Client client = proxy.client(HOST)) {
            client.send("GET / HTTP/1.1\r\nHost: " + HOST + "\r\nCookie: " + big + "\r\n\r\n");
            Response response = client.read();
            assertThat(response.body()).isEqualTo("received");
            assertThat(response.headers()).contains(big);
        }
    }

    @Test
    void aClientLeavingAStream_closesTheHttpsUpstreamConnection() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        int port = proxy.https(new RootCertificateCreator().generateRootCertificate("Backend CA"), (in, out, socket) -> {
            head(in);
            try {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n".getBytes());
                // Never reads again, as a backend that only streams: it learns of a close by failing to write.
                for (int i = 0; i < 3000; i++) {
                    out.write("data: tick\n\n".getBytes());
                    out.flush();
                    Thread.sleep(10);
                }
            } catch (java.io.IOException e) {
                released.countDown();
            }
        });
        route("""
                virtual:
                  - domain: %s
                    location:
                      - host: https://127.0.0.1:%d
                        path: [ /** ]
                """.formatted(HOST, port));

        try (Client client = proxy.client(HOST)) {
            client.send("GET / HTTP/1.1\r\nHost: " + HOST + "\r\n\r\n");
            assertThat(head(client.in)).contains(" 200 ");
        }

        assertThat(released.await(6, TimeUnit.SECONDS)).as("the upstream TCP connection was closed").isTrue();
    }

    @Test
    void aBodyDelimitedByClose_isStreamedAsItArrives() throws Exception {
        routeAll(proxy.http((in, out, socket) -> {
            head(in);
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n".getBytes());
            out.flush();
            out.write("data: first\n\n".getBytes());
            out.flush();
            Thread.sleep(8000); // longer than the client waits
        }));

        try (Client client = proxy.client(HOST)) {
            client.send("GET / HTTP/1.1\r\nHost: " + HOST + "\r\n\r\n");
            assertThat(head(client.in)).contains(" 200 ");
            assertThat(new String(client.in.readNBytes(13))).isEqualTo("data: first\n\n");
        }
    }

    @Test
    void anOversizedHeaderBlock_isNotRelayed() throws Exception {
        java.util.concurrent.atomic.AtomicInteger received = new java.util.concurrent.atomic.AtomicInteger();
        routeAll(proxy.http((in, out, socket) -> {
            if (!head(in).isEmpty()) received.incrementAndGet();
            reply(out, "ok");
        }));
        String tooManyLines = "X: 1\r\n".repeat(1001);
        String tooManyBytes = ("X: " + "y".repeat(60 * 1024) + "\r\n").repeat(5);

        for (String headers : List.of(tooManyLines, tooManyBytes)) {
            try (Client client = proxy.client(HOST)) {
                client.send("GET / HTTP/1.1\r\nHost: " + HOST + "\r\n" + headers + "\r\n");
                assertThat(client.read().status()).doesNotContain(" 200 ");
            } catch (java.io.IOException expected) {
                // closed with the rest of the block unread
            }
        }
        assertThat(received).hasValue(0);
    }

    @Test
    void lowerCaseMethodsAndTheHttp2Preface_stayRejected() throws Exception {
        routeAll(proxy.http(always("ok")));

        for (String line : List.of("get / HTTP/1.1", "PRI * HTTP/2.0")) {
            try (Client client = proxy.client(HOST)) {
                client.send(line + "\r\nHost: " + HOST + "\r\n\r\n");
                assertThat(client.read().status()).as(line).contains(" 400 ");
            }
        }
    }

    @Test
    void extensionMethodsAreRelayed() throws Exception {
        routeAll(proxy.http(always("ok")));

        try (Client client = proxy.client(HOST)) {
            client.send("PROPFIND / HTTP/1.1\r\nHost: " + HOST + "\r\n\r\n");
            assertThat(client.read().status()).contains(" 200 ");
        }
    }

    @Test
    void aHostWithTheDefaultPortOrInAnotherCase_findsItsVhost() throws Exception {
        routeAll(proxy.http(always("ok")));

        for (String host : List.of(HOST + ":443", "Relay.TEST", HOST + ".")) {
            try (Client client = proxy.client(HOST)) {
                assertThat(client.get(host, "/").status()).as(host).contains(" 200 ");
            }
        }
    }

    @Test
    void tls12And13_acceptARepeatedHandshake() throws Exception {
        routeAll(proxy.http(always("ok")));

        for (String protocol : List.of("TLSv1.2", "TLSv1.3")) {
            for (int i = 0; i < 3; i++) {
                try (Client client = proxy.client(HOST, protocol)) {
                    assertThat(client.get(HOST, "/").status()).as(protocol + " #" + i).contains(" 200 ");
                }
            }
        }
    }

    @Test
    void aNameLongerThan64Characters_getsACertificate() throws Exception {
        String host = "a".repeat(40) + "." + "b".repeat(30) + ".relay.test";
        int port = proxy.http(always("ok"));
        route("""
                virtual:
                  - domain: %s
                    location:
                      - host: http://127.0.0.1:%d
                        path: [ /** ]
                """.formatted(host, port));

        try (Client client = proxy.client(host)) {
            assertThat(client.get(host, "/").body()).isEqualTo("ok");
        }
    }
}
