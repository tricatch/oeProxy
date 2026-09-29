package tricatch.oe.proxy.http.io;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class HeaderLinesTest {

    private HeaderLines responseLines(String statusLine, String... headerLines) {
        HeaderLines lines = new HeaderLines(1 + headerLines.length);
        lines.addHeaderLine(new ByteBuffer(statusLine.getBytes()));
        for (String h : headerLines) {
            lines.addHeaderLine(new ByteBuffer(h.getBytes()));
        }
        return lines;
    }

    private HeaderLines requestLines(String requestLine, String... headerLines) {
        HeaderLines lines = new HeaderLines(1 + headerLines.length);
        lines.addHeaderLine(new ByteBuffer(requestLine.getBytes()));
        for (String h : headerLines) {
            lines.addHeaderLine(new ByteBuffer(h.getBytes()));
        }
        return lines;
    }

    @Test
    void contentLength_isClassifiedAsContentLength() {
        HeaderLines lines = responseLines("HTTP/1.1 200 OK", "Content-Length: 42");
        assertThat(lines.parseHttpResponse(false).getBodyStream()).isEqualTo(HttpStream.CONTENT_LENGTH);
    }

    @Test
    void transferEncodingChunked_isClassifiedAsChunked() {
        HeaderLines lines = responseLines("HTTP/1.1 200 OK", "Transfer-Encoding: chunked");
        assertThat(lines.parseHttpResponse(false).getBodyStream()).isEqualTo(HttpStream.CHUNKED);
    }

    @Test
    void status204_isClassifiedAsNone_evenWithoutHeaders() {
        HeaderLines lines = responseLines("HTTP/1.1 204 No Content");
        assertThat(lines.parseHttpResponse(false).getBodyStream()).isEqualTo(HttpStream.NONE);
    }

    @Test
    void status304_isClassifiedAsNone_evenWithoutHeaders() {
        HeaderLines lines = responseLines("HTTP/1.1 304 Not Modified");
        assertThat(lines.parseHttpResponse(false).getBodyStream()).isEqualTo(HttpStream.NONE);
    }

    @Test
    void informational1xx_isClassifiedAsNone_evenWithoutHeaders() {
        HeaderLines lines = responseLines("HTTP/1.1 100 Continue");
        assertThat(lines.parseHttpResponse(false).getBodyStream()).isEqualTo(HttpStream.NONE);
    }

    @Test
    void websocketUpgrade_isClassifiedAsWebsocket() {
        HeaderLines lines = responseLines("HTTP/1.1 101 Switching Protocols",
                "Upgrade: websocket",
                "Connection: Upgrade");
        assertThat(lines.parseHttpResponse(false).getBodyStream()).isEqualTo(HttpStream.WEBSOCKET);
    }

    @Test
    void noContentLengthAndNoTransferEncoding_isClassifiedAsUntilClose() {
        // RFC 7230 3.3.3 case 7: body is delimited by the server closing the connection
        HeaderLines lines = responseLines("HTTP/1.1 200 OK");
        assertThat(lines.parseHttpResponse(false).getBodyStream()).isEqualTo(HttpStream.UNTIL_CLOSE);
    }

    @Test
    void headResponse_isClassifiedAsNone_evenWithoutHeaders() {
        // A HEAD response never has a body, even without Content-Length/Transfer-Encoding — must
        // not fall through to UNTIL_CLOSE like a same-shaped GET response would.
        HeaderLines lines = responseLines("HTTP/1.1 200 OK");
        assertThat(lines.parseHttpResponse(true).getBodyStream()).isEqualTo(HttpStream.NONE);
    }

    @Test
    void headResponse_isClassifiedAsNone_evenWithContentLength() {
        // A HEAD response may carry a Content-Length describing what a GET would return, without
        // actually sending a body.
        HeaderLines lines = responseLines("HTTP/1.1 200 OK", "Content-Length: 1234");
        assertThat(lines.parseHttpResponse(true).getBodyStream()).isEqualTo(HttpStream.NONE);
    }

    // ── request-smuggling framing validation ────────────────────────────────────────

    @Test
    void request_singleContentLength_isAccepted() {
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Content-Length: 5");
        assertThatNoException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_singleChunkedTransferEncoding_isAccepted() {
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Transfer-Encoding: chunked");
        assertThatNoException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_duplicateContentLength_isRejected() {
        // CL.CL smuggling: two Content-Length headers, possibly disagreeing on the body length.
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Content-Length: 5", "Content-Length: 10");
        assertThatIllegalArgumentException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_duplicateContentLength_isRejected_evenWhenIdentical() {
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Content-Length: 5", "Content-Length: 5");
        assertThatIllegalArgumentException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_contentLengthAndTransferEncodingTogether_isRejected() {
        // The classic CL.TE / TE.CL smuggling setup.
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Content-Length: 5", "Transfer-Encoding: chunked");
        assertThatIllegalArgumentException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_duplicateTransferEncoding_isRejected() {
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Transfer-Encoding: chunked", "Transfer-Encoding: chunked");
        assertThatIllegalArgumentException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_transferEncodingWithSurroundingWhitespace_isAccepted() {
        // Leading/trailing whitespace around the value is trimmed by the normal header-value
        // parsing, so this is just a plain "chunked" - not an obfuscation attempt.
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Transfer-Encoding: chunked ");
        assertThatNoException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_obfuscatedTransferEncodingValue_isRejected() {
        // Not exactly "chunked" - a common obfuscation trick to slip past one hop's parser while
        // still being interpreted as chunked by the other.
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Transfer-Encoding: identity, chunked");
        assertThatIllegalArgumentException().isThrownBy(lines::parseHttpRequest);
    }

    // ── header-syntax validation (obs-fold / whitespace-before-colon) ──────────────────

    @Test
    void request_wellFormedHeaders_areAccepted() {
        HeaderLines lines = requestLines("GET /x HTTP/1.1", "Host: a", "X-Foo: bar");
        assertThatNoException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_colonInHeaderValue_doesNotFalsePositive() {
        // The colon-search must stop at the field-name/value boundary, not object to a colon
        // appearing later in the value itself.
        HeaderLines lines = requestLines("GET /x HTTP/1.1", "Host: a",
                "Cookie: session=abc; expires=Wed, 21 Oct 2026 07:28:00 GMT");
        assertThatNoException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_emptyHeaderValue_isAccepted() {
        HeaderLines lines = requestLines("GET /x HTTP/1.1", "Host: a", "X-Empty:");
        assertThatNoException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_whitespaceBeforeColon_isRejected() {
        // RFC 9112 5.1: a recipient MUST reject this rather than strip the whitespace, since
        // some servers honor the header under its name and others don't.
        HeaderLines lines = requestLines("GET /x HTTP/1.1", "Host: a", "X-Foo : bar");
        assertThatIllegalArgumentException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_obsFoldContinuationLine_isRejected() {
        // RFC 7230 3.2.4: a header line beginning with SP/HTAB is legacy line-folding, which
        // must be rejected rather than treated as its own independent header.
        HeaderLines lines = requestLines("GET /x HTTP/1.1", "Host: a", " continuation-of-previous-value");
        assertThatIllegalArgumentException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_headerLineMissingColon_isRejected() {
        HeaderLines lines = requestLines("GET /x HTTP/1.1", "Host: a", "NotAHeaderLine");
        assertThatIllegalArgumentException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void response_whitespaceBeforeColon_isRejected() {
        HeaderLines lines = responseLines("HTTP/1.1 200 OK", "X-Foo : bar");
        assertThatIllegalArgumentException().isThrownBy(() -> lines.parseHttpResponse(false));
    }

    @Test
    void response_obsFoldContinuationLine_isRejected() {
        HeaderLines lines = responseLines("HTTP/1.1 200 OK", " continuation-of-previous-value");
        assertThatIllegalArgumentException().isThrownBy(() -> lines.parseHttpResponse(false));
    }

    // ── removeHeadersNamed / setHeaderLine (per-location header add/remove rules) ──────

    @Test
    void removeHeadersNamed_removesAllMatchingLines_caseInsensitively() {
        HeaderLines lines = requestLines("GET /x HTTP/1.1", "Host: a", "X-Foo: 1", "x-foo: 2", "X-Bar: keep");
        lines.removeHeadersNamed("X-Foo");
        assertThat(lines.hasHeader("X-Foo".getBytes())).isFalse();
        assertThat(lines.getHeaderValueAsString("X-Bar".getBytes())).isEqualTo("keep");
    }

    @Test
    void removeHeadersNamed_neverRemovesRequestLine() {
        HeaderLines lines = requestLines("GET /X-Foo HTTP/1.1", "Host: a");
        lines.removeHeadersNamed("GET");
        assertThat(lines.size()).isEqualTo(2);
    }

    @Test
    void setHeaderLine_addsNewHeader() {
        HeaderLines lines = requestLines("GET /x HTTP/1.1", "Host: a");
        lines.setHeaderLine("X-Custom: injected");
        assertThat(lines.getHeaderValueAsString("X-Custom".getBytes())).isEqualTo("injected");
    }

    @Test
    void setHeaderLine_replacesExistingHeaderOfSameName() {
        HeaderLines lines = requestLines("GET /x HTTP/1.1", "Host: a", "X-Custom: old");
        lines.setHeaderLine("X-Custom: new");
        assertThat(lines.getHeaderValueAsString("X-Custom".getBytes())).isEqualTo("new");
        // exactly one X-Custom line remains, not two
        int count = 0;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).toString().toLowerCase().startsWith("x-custom")) count++;
        }
        assertThat(count).isEqualTo(1);
    }

    @Test
    void setHeaderLine_rejectsEmbeddedCrlf_toPreventHeaderInjection() {
        HeaderLines lines = requestLines("GET /x HTTP/1.1", "Host: a");
        int sizeBefore = lines.size();
        lines.setHeaderLine("X-Custom: value\r\nX-Injected: evil");
        assertThat(lines.size()).isEqualTo(sizeBefore);
        assertThat(lines.hasHeader("X-Injected".getBytes())).isFalse();
    }

    @Test
    void setHeaderLine_ignoresMalformedLineWithNoColon() {
        HeaderLines lines = requestLines("GET /x HTTP/1.1", "Host: a");
        int sizeBefore = lines.size();
        lines.setHeaderLine("NotAHeaderLine");
        assertThat(lines.size()).isEqualTo(sizeBefore);
    }

    // ── Content-Length larger than an int ────────────────────────────────────

    @Test
    void request_contentLengthAboveIntMax_isFramedByContentLength_notTreatedAsNoBody() {
        // 3,000,000,000 does not fit in an int. It used to be read as "no Content-Length", so a POST
        // was classified as having no body and its body bytes were then parsed as the next request.
        HeaderLines lines = requestLines("POST /upload HTTP/1.1", "Host: a", "Content-Length: 3000000000");

        var request = lines.parseHttpRequest();

        assertThat(request.getHttpStream()).isEqualTo(HttpStream.CONTENT_LENGTH);
        assertThat(request.getContentLength()).isEqualTo(3_000_000_000L);
    }

    @Test
    void response_contentLengthAboveIntMax_isFramedByContentLength_notUntilClose() {
        // ... and a response used to fall back to "body ends when the connection closes", which a
        // keep-alive upstream never does.
        HeaderLines lines = responseLines("HTTP/1.1 200 OK", "Content-Length: 5000000000");

        var response = lines.parseHttpResponse(false);

        assertThat(response.getBodyStream()).isEqualTo(HttpStream.CONTENT_LENGTH);
        assertThat(response.getContentLength()).isEqualTo(5_000_000_000L);
    }

    @Test
    void request_contentLengthBeyondEvenALong_isRejected() {
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Content-Length: 99999999999999999999");
        assertThatIllegalArgumentException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void copy_isIndependentOfTheOriginal() {
        HeaderLines original = requestLines("GET /a HTTP/1.1", "Host: a", "X-One: 1");
        HeaderLines copy = original.copy();

        assertThat(copy.toString()).isEqualTo(original.toString());

        // Mutating the original's list and its backing bytes must not show up in the copy.
        original.get(1).getBuffer()[0] = 'Z';
        original.removeHeadersNamed("X-One");
        original.setHeaderLine("X-Two: 2");

        assertThat(copy).hasSize(3);
        assertThat(copy.toString()).isEqualTo("GET /a HTTP/1.1\r\nHost: a\r\nX-One: 1\r\n");
        assertThat(copy.get(1)).isNotSameAs(original.get(1));
    }
}
