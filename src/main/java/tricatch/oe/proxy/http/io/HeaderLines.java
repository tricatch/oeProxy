package tricatch.oe.proxy.http.io;

import java.util.ArrayList;
import tricatch.oe.proxy.http.HTTP;

/**
 * ArrayList extension for storing HTTP header lines
 * Provides convenient methods for header line management
 */
public class HeaderLines extends ArrayList<ByteBuffer> {
    
    /**
     * Constructor with initial capacity
     * @param initialCapacity initial capacity for the list
     */
    public HeaderLines(int initialCapacity) {
        super(initialCapacity);
    }
    
    /**
     * Deep copy: every line gets its own trimmed byte array, so the copy is independent of later
     * changes to this list or its buffers (used for monitor event snapshots).
     * @return a new HeaderLines holding copies of all lines
     */
    public HeaderLines copy() {
        HeaderLines copy = new HeaderLines(Math.max(size(), 1));
        for (ByteBuffer line : this) {
            copy.add(line == null ? null : new ByteBuffer(java.util.Arrays.copyOf(line.getBuffer(), line.getLength())));
        }
        return copy;
    }

    /**
     * Add header line as ByteBuffer
     * @param headerLine header line ByteBuffer
     * @return true if added successfully
     */
    public boolean addHeaderLine(ByteBuffer headerLine) {
        return add(headerLine);
    }
    

    
    /**
     * Find header line by name using byte array (case-insensitive)
     * @param headerNameBytes header name as byte array
     * @return header line as ByteBuffer, or null if not found
     */
    public ByteBuffer findHeaderLine(byte[] headerNameBytes) {
        // Skip first line (request/response line) and search from second line onwards
        for (int i = 1; i < size(); i++) {
            ByteBuffer headerBuffer = get(i);
            if (startsWithIgnoreCase(headerBuffer.getBuffer(), headerBuffer.getLength(), headerNameBytes) >= 0) {
                return headerBuffer;
            }
        }
        return null;
    }
    

    
    /**
     * Get header value as string by name using byte array (case-insensitive)
     * @param headerNameBytes header name as byte array
     * @return header value as string, or null if not found
     */
    public String getHeaderValueAsString(byte[] headerNameBytes) {
        // Skip first line (request/response line) and search from second line onwards
        for (int i = 1; i < size(); i++) {
            ByteBuffer headerBuffer = get(i);
            int colonIndex = startsWithIgnoreCase(headerBuffer.getBuffer(), headerBuffer.getLength(), headerNameBytes);
            if (colonIndex >= 0 && colonIndex < headerBuffer.getLength() - 1) {
                return trimHeaderValueAsString(headerBuffer.getBuffer(), headerBuffer.getLength(), colonIndex + 1);
            }
        }
        return null;
    }
    
    /**
     * Get header value as integer by name using byte array
     * @param headerNameBytes header name as byte array
     * @return header value as integer, or null if not found or not a number
     */
    public Integer getHeaderValueAsInt(byte[] headerNameBytes) {
        // Skip first line (request/response line) and search from second line onwards
        for (int i = 1; i < size(); i++) {
            ByteBuffer headerBuffer = get(i);
            int colonIndex = startsWithIgnoreCase(headerBuffer.getBuffer(), headerBuffer.getLength(), headerNameBytes);
            if (colonIndex >= 0 && colonIndex < headerBuffer.getLength() - 1) {
                return trimHeaderValueAsInt(headerBuffer.getBuffer(), headerBuffer.getLength(), colonIndex + 1);
            }
        }
        return null;
    }
    
    /**
     * Get header value as long by name using byte array
     * @param headerNameBytes header name as byte array
     * @return header value as long, or null if not found or not a number
     */
    public Long getHeaderValueAsLong(byte[] headerNameBytes) {
        // Skip first line (request/response line) and search from second line onwards
        for (int i = 1; i < size(); i++) {
            ByteBuffer headerBuffer = get(i);
            int colonIndex = startsWithIgnoreCase(headerBuffer.getBuffer(), headerBuffer.getLength(), headerNameBytes);
            if (colonIndex >= 0 && colonIndex < headerBuffer.getLength() - 1) {
                return trimHeaderValueAsLong(headerBuffer.getBuffer(), headerBuffer.getLength(), colonIndex + 1);
            }
        }
        return null;
    }
    
    /**
     * Check if header exists using byte array
     * @param headerNameBytes header name as byte array
     * @return true if header exists
     */
    public boolean hasHeader(byte[] headerNameBytes) {
        return findHeaderLine(headerNameBytes) != null;
    }

    /**
     * Remove every header line matching this name (case-insensitive). Used to apply
     * per-location "remove header" rules before forwarding a request/response.
     * @param headerName header name, without the trailing colon
     */
    public void removeHeadersNamed(String headerName) {
        byte[] headerNameBytes = headerName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = size() - 1; i >= 1; i--) {
            ByteBuffer headerBuffer = get(i);
            if (startsWithIgnoreCase(headerBuffer.getBuffer(), headerBuffer.getLength(), headerNameBytes) >= 0) {
                remove(i);
            }
        }
    }

    /**
     * Set a header line built from a "Name: Value" string, replacing any existing header
     * with the same name. Used to apply per-location "add header" rules before forwarding.
     * A CR or LF anywhere in headerLine is rejected outright rather than forwarded — this
     * string comes from admin-editable vhost config (attacker-controllable: any authenticated
     * user's own vhost), and writeHeaders() would otherwise serialize an embedded CRLF as real
     * extra header lines on the wire, letting one configured header inject others.
     * @param headerLine full header line, e.g. "X-Custom-Header: value"
     */
    public void setHeaderLine(String headerLine) {
        if (headerLine.indexOf('\r') >= 0 || headerLine.indexOf('\n') >= 0) return;
        int colon = headerLine.indexOf(':');
        if (colon <= 0) return;
        removeHeadersNamed(headerLine.substring(0, colon).trim());
        add(new ByteBuffer(headerLine.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    
    /**
     * Check if HTTP method is valid
     * @param method HTTP method to validate
     * @return true if method is valid
     */
    private boolean isValidHttpMethod(String method) {
        if (method == null || method.isEmpty()) {
            return false;
        }
        
        // Standard HTTP methods
        return "GET".equals(method) || 
               "POST".equals(method) || 
               "PUT".equals(method) || 
               "DELETE".equals(method) || 
               "HEAD".equals(method) || 
               "OPTIONS".equals(method) || 
               "TRACE".equals(method) || 
               "CONNECT".equals(method) || 
               "PATCH".equals(method);
    }
    
    /**
     * Check if byte array starts with another byte array (case-insensitive)
     * @param source source byte array
     * @param sourceLength actual length of source data
     * @param prefix prefix byte array to check
     * @return colon index if source starts with prefix, -1 otherwise
     */
    private int startsWithIgnoreCase(byte[] source, int sourceLength, byte[] prefix) {
        if (sourceLength < prefix.length + 1) { // +1 for colon
            return -1;
        }
        
        // Check prefix (case-insensitive)
        for (int i = 0; i < prefix.length; i++) {
            byte sourceByte = source[i];
            byte prefixByte = prefix[i];
            
            // Convert to lowercase for comparison
            if (sourceByte >= 'A' && sourceByte <= 'Z') {
                sourceByte = (byte) (sourceByte + 32);
            }
            if (prefixByte >= 'A' && prefixByte <= 'Z') {
                prefixByte = (byte) (prefixByte + 32);
            }
            
            if (sourceByte != prefixByte) {
                return -1;
            }
        }
        
        // Check for colon after prefix (with optional spaces)
        int colonIndex = prefix.length;
        while (colonIndex < sourceLength && (source[colonIndex] == ' ' || source[colonIndex] == '\t')) {
            colonIndex++;
        }
        return (colonIndex < sourceLength && source[colonIndex] == ':') ? colonIndex : -1;
    }
    

    

    
    /**
     * Trim header value and convert to string (no memory copy)
     * @param headerBytes full header line bytes
     * @param headerLength actual length of header data
     * @param startIndex start index of value (after colon)
     * @return trimmed header value as string, or null if empty
     */
    private String trimHeaderValueAsString(byte[] headerBytes, int headerLength, int startIndex) {
        if (startIndex >= headerLength) {
            return null;
        }
        
        // Find start (skip leading spaces)
        int start = startIndex;
        while (start < headerLength && (headerBytes[start] == ' ' || headerBytes[start] == '\t')) {
            start++;
        }
        
        // Find end (skip trailing spaces)
        int end = headerLength - 1;
        while (end >= start && (headerBytes[end] == ' ' || headerBytes[end] == '\t')) {
            end--;
        }
        
        if (start > end) {
            return null;
        }
        
        // Convert directly to string without copying
        return new String(headerBytes, start, end - start + 1);
    }
    
    /**
     * Trim header value and convert to integer (no memory copy)
     * @param headerBytes full header line bytes
     * @param headerLength actual length of header data
     * @param startIndex start index of value (after colon)
     * @return trimmed header value as integer, or null if not a number
     */
    private Integer trimHeaderValueAsInt(byte[] headerBytes, int headerLength, int startIndex) {
        if (startIndex >= headerLength) {
            return null;
        }
        
        // Find start (skip leading spaces)
        int start = startIndex;
        while (start < headerLength && (headerBytes[start] == ' ' || headerBytes[start] == '\t')) {
            start++;
        }
        
        // Find end (skip trailing spaces)
        int end = headerLength - 1;
        while (end >= start && (headerBytes[end] == ' ' || headerBytes[end] == '\t')) {
            end--;
        }
        
        if (start > end) {
            return null;
        }
        
        // Parse integer directly from byte array
        try {
            return parseIntFromBytes(headerBytes, start, end - start + 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * Trim header value and convert to long (no memory copy)
     * @param headerBytes full header line bytes
     * @param headerLength actual length of header data
     * @param startIndex start index of value (after colon)
     * @return trimmed header value as long, or null if not a number
     */
    private Long trimHeaderValueAsLong(byte[] headerBytes, int headerLength, int startIndex) {
        if (startIndex >= headerLength) {
            return null;
        }
        
        // Find start (skip leading spaces)
        int start = startIndex;
        while (start < headerLength && (headerBytes[start] == ' ' || headerBytes[start] == '\t')) {
            start++;
        }
        
        // Find end (skip trailing spaces)
        int end = headerLength - 1;
        while (end >= start && (headerBytes[end] == ' ' || headerBytes[end] == '\t')) {
            end--;
        }
        
        if (start > end) {
            return null;
        }
        
        // Parse long directly from byte array
        try {
            return parseLongFromBytes(headerBytes, start, end - start + 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * Parse integer from byte array without creating string
     * @param bytes byte array
     * @param offset start offset
     * @param length number of bytes to parse
     * @return parsed integer
     * @throws NumberFormatException if not a valid integer
     */
    private int parseIntFromBytes(byte[] bytes, int offset, int length) {
        if (length == 0) {
            throw new NumberFormatException("Empty string");
        }
        
        long result = 0;
        boolean negative = false;
        int i = offset;

        // Check for sign
        if (bytes[i] == '-') {
            negative = true;
            i++;
        } else if (bytes[i] == '+') {
            i++;
        }

        if (i >= offset + length) {
            throw new NumberFormatException("No digits");
        }

        // Parse digits
        while (i < offset + length) {
            byte b = bytes[i];
            if (b < '0' || b > '9') {
                throw new NumberFormatException("Invalid digit");
            }
            result = result * 10 + (b - '0');
            if (result > Integer.MAX_VALUE) {
                throw new NumberFormatException("Value out of int range");
            }
            i++;
        }

        return negative ? (int) -result : (int) result;
    }

    /**
     * Parse long from byte array without creating string
     * @param bytes byte array
     * @param offset start offset
     * @param length number of bytes to parse
     * @return parsed long
     * @throws NumberFormatException if not a valid long
     */
    private long parseLongFromBytes(byte[] bytes, int offset, int length) {
        if (length == 0) {
            throw new NumberFormatException("Empty string");
        }
        
        long result = 0;
        boolean negative = false;
        int i = offset;

        // Check for sign
        if (bytes[i] == '-') {
            negative = true;
            i++;
        } else if (bytes[i] == '+') {
            i++;
        }

        if (i >= offset + length) {
            throw new NumberFormatException("No digits");
        }

        // Parse digits, guarding against long overflow before it happens
        while (i < offset + length) {
            byte b = bytes[i];
            if (b < '0' || b > '9') {
                throw new NumberFormatException("Invalid digit");
            }
            int digit = b - '0';
            if (result > (Long.MAX_VALUE - digit) / 10) {
                throw new NumberFormatException("Value out of long range");
            }
            result = result * 10 + digit;
            i++;
        }

        return negative ? -result : result;
    }

    /**
     * Get total size of all header lines in bytes
     * @return total byte size
     */
    public int getTotalByteSize() {
        int totalSize = 0;
        for (ByteBuffer headerBuffer : this) {
            totalSize += headerBuffer.getLength();
            totalSize += 2; // Add CR-LF for each line
        }
        return totalSize;
    }
    
    /**
     * Clear all header lines
     */
    @Override
    public void clear() {
        super.clear();
    }
    
    /**
     * Get header lines as formatted string
     * @return formatted header lines string
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (ByteBuffer headerBuffer : this) {
            sb.append(headerBuffer.toString()).append("\r\n");
        }
        return sb.toString();
    }
    
    /**
     * Parse HTTP request from header lines
     * Parses the first line to extract method, path, and version
     * Determines body stream type based on headers
     * @return HttpRequest object with parsed information
     * @throws IllegalArgumentException if request line is invalid
     */
    public HttpRequest parseHttpRequest() {
        if (isEmpty()) {
            throw new IllegalArgumentException("HeaderLines is empty");
        }
        
        // Parse first line (request line)
        ByteBuffer requestLine = get(0);
        byte[] requestBytes = requestLine.getBuffer();
        int requestLength = requestLine.getLength();
        
        // Find first space (separates method and path)
        int firstSpaceIndex = -1;
        for (int i = 0; i < requestLength; i++) {
            if (requestBytes[i] == HTTP.SPACE) {
                firstSpaceIndex = i;
                break;
            }
        }
        
        if (firstSpaceIndex == -1) {
            throw new IllegalArgumentException("Invalid request line: no space found after method");
        }
        
        // Find second space (separates path and version)
        int secondSpaceIndex = -1;
        for (int i = firstSpaceIndex + 1; i < requestLength; i++) {
            if (requestBytes[i] == HTTP.SPACE) {
                secondSpaceIndex = i;
                break;
            }
        }
        
        if (secondSpaceIndex == -1) {
            throw new IllegalArgumentException("Invalid request line: no space found after path");
        }
        
        // Extract method, path, and version
        String method = new String(requestBytes, 0, firstSpaceIndex);
        String path = new String(requestBytes, firstSpaceIndex + 1, secondSpaceIndex - firstSpaceIndex - 1);
        String version = new String(requestBytes, secondSpaceIndex + 1, requestLength - secondSpaceIndex - 1);
        
        // Validate HTTP method
        if (!isValidHttpMethod(method)) {
            throw new IllegalArgumentException("Invalid HTTP method: " + method);
        }

        // Reject malformed header syntax (obs-fold, whitespace before colon) that a backend
        // could interpret differently than this proxy — see validateHeaderSyntax() for why.
        validateHeaderSyntax();

        // Reject ambiguous Content-Length/Transfer-Encoding framing before it's ever forwarded
        // upstream — the classic CL.TE/TE.CL/CL.CL request-smuggling setup.
        validateFraming();

        // Extract host from headers
        String host = getHeaderValueAsString(HTTP.HEADER.HOST);
        
        // Extract connection from headers
        String connection = getHeaderValueAsString(HTTP.HEADER.CONNECTION);
        
        // Extract content length from headers
        Long contentLength = getHeaderValueAsLong(HTTP.HEADER.CONTENT_LENGTH);
        
        // Determine body stream type
        HttpStream httpStream = determineRequestBodyStreamType();
        
        return new HttpRequest(method, path, version, host, connection, contentLength, httpStream, this);
    }
    
    /**
     * Rejects malformed header-line syntax that a backend server could parse differently than
     * this proxy does, letting the two disagree about where headers begin/end (the same class
     * of ambiguity validateFraming() closes for Content-Length/Transfer-Encoding, extended to
     * header syntax itself):
     * <ul>
     *   <li>obs-fold — a header line beginning with a space/tab is legacy line-folding
     *       (RFC 7230 §3.2.4) that must be rejected rather than treated as its own header;</li>
     *   <li>whitespace between the field-name and the colon (e.g. "Foo : bar") — RFC 9112 §5.1
     *       requires a recipient to reject this outright rather than strip the whitespace,
     *       since some servers honor the header and others don't.</li>
     * </ul>
     * @throws IllegalArgumentException if any header line has malformed syntax
     */
    private void validateHeaderSyntax() {
        for (int i = 1; i < size(); i++) {
            ByteBuffer headerBuffer = get(i);
            byte[] buf = headerBuffer.getBuffer();
            int len = headerBuffer.getLength();
            if (len == 0) continue;

            if (buf[0] == ' ' || buf[0] == '\t') {
                throw new IllegalArgumentException("Malformed header: obsolete line folding is not supported");
            }

            int colon = -1;
            for (int j = 0; j < len; j++) {
                if (buf[j] == ':') {
                    colon = j;
                    break;
                }
                if (buf[j] == ' ' || buf[j] == '\t') {
                    throw new IllegalArgumentException("Malformed header: whitespace before colon in header field-name");
                }
            }
            if (colon <= 0) {
                throw new IllegalArgumentException("Malformed header: missing colon");
            }
        }
    }

    /**
     * Rejects a request whose Content-Length/Transfer-Encoding framing is ambiguous. A
     * conforming client never needs to send more than one Content-Length header, both
     * Content-Length and Transfer-Encoding together, or a Transfer-Encoding value other than
     * exactly "chunked" (RFC 9112 §6.1, §6.3) — anything else is either a broken client or an
     * attempt to make this proxy and the upstream server disagree about where one request ends
     * and the next begins (CL.TE / TE.CL / CL.CL request smuggling).
     * @throws IllegalArgumentException if the framing is ambiguous
     */
    private void validateFraming() {
        int contentLengthCount = 0;
        int transferEncodingCount = 0;
        String transferEncodingValue = null;

        for (int i = 1; i < size(); i++) {
            ByteBuffer headerBuffer = get(i);
            byte[] buf = headerBuffer.getBuffer();
            int len = headerBuffer.getLength();

            int clColon = startsWithIgnoreCase(buf, len, HTTP.HEADER.CONTENT_LENGTH);
            if (clColon >= 0) {
                contentLengthCount++;
                // A negative Content-Length would otherwise be parsed as-is and then fail the
                // ">= 0" check in determine*BodyStreamType(), silently downgrading a request/response
                // that actually has a body to HttpStream.NONE — desynchronizing this keep-alive
                // connection's framing from here on. Reject it outright instead.
                if (clColon < len - 1) {
                    Long clValue = trimHeaderValueAsLong(buf, len, clColon + 1);
                    if (clValue == null || clValue < 0) {
                        throw new IllegalArgumentException("Ambiguous framing: invalid Content-Length value");
                    }
                }
                continue;
            }

            int teColon = startsWithIgnoreCase(buf, len, HTTP.HEADER.TRANSFER_ENCODING);
            if (teColon >= 0) {
                transferEncodingCount++;
                if (teColon < len - 1) {
                    transferEncodingValue = trimHeaderValueAsString(buf, len, teColon + 1);
                }
            }
        }

        if (contentLengthCount > 1) {
            throw new IllegalArgumentException("Ambiguous framing: multiple Content-Length headers");
        }
        if (transferEncodingCount > 1) {
            throw new IllegalArgumentException("Ambiguous framing: multiple Transfer-Encoding headers");
        }
        if (contentLengthCount > 0 && transferEncodingCount > 0) {
            throw new IllegalArgumentException("Ambiguous framing: Content-Length and Transfer-Encoding both present");
        }
        if (transferEncodingCount > 0 && (transferEncodingValue == null || !"chunked".equalsIgnoreCase(transferEncodingValue))) {
            throw new IllegalArgumentException("Ambiguous framing: unsupported Transfer-Encoding value '" + transferEncodingValue + "'");
        }
    }

    /**
     * Determine body stream type based on headers
     * @return BodyStream type
     */
    private HttpStream determineRequestBodyStreamType() {
        // Check for WebSocket upgrade
        String upgrade = getHeaderValueAsString(HTTP.HEADER.UPGRADE);
        String connection = getHeaderValueAsString(HTTP.HEADER.CONNECTION);
        if (upgrade != null && connection != null &&
            "websocket".equalsIgnoreCase(upgrade) &&
            containsIgnoreCase(connection, "upgrade")) {
            return HttpStream.WEBSOCKET;
        }

        // Check for Transfer-Encoding: chunked
        String transferEncoding = getHeaderValueAsString(HTTP.HEADER.TRANSFER_ENCODING);
        if (transferEncoding != null && containsIgnoreCase(transferEncoding, "chunked")) {
            return HttpStream.CHUNKED;
        }

        // Check for Content-Length
        Long contentLength = getHeaderValueAsLong(HTTP.HEADER.CONTENT_LENGTH);
        if (contentLength != null && contentLength >= 0) {
            return HttpStream.CONTENT_LENGTH;
        }
        
        // Check if method typically has no-body
        if (!isEmpty()) {
            ByteBuffer requestLine = get(0);
            byte[] requestBytes = requestLine.getBuffer();
            int requestLength = requestLine.getLength();
            
            // Find method end (first space)
            int firstSpaceIndex = -1;
            for (int i = 0; i < requestLength; i++) {
                if (requestBytes[i] == HTTP.SPACE) {
                    firstSpaceIndex = i;
                    break;
                }
            }
            
            if (firstSpaceIndex > 0) {
                String method = new String(requestBytes, 0, firstSpaceIndex);
                if ("GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method)) {
                    return HttpStream.NONE;
                }
            }
        }
        
        // Default to no-body
        return HttpStream.NONE;
    }
    
    /**
     * Parse HTTP response from header lines
     * Parses the first line to extract version, status code, and status message
     * Determines body stream type based on headers
     * @param isHeadRequest true if the request this is a response to used the HEAD method —
     *                       such responses never have a body regardless of what the headers say
     * @return HttpResponse object with parsed information
     * @throws IllegalArgumentException if response line is invalid
     */
    public HttpResponse parseHttpResponse(boolean isHeadRequest) {
        if (isEmpty()) {
            throw new IllegalArgumentException("HeaderLines is empty");
        }
        
        // Parse first line (response line)
        ByteBuffer responseLine = get(0);
        byte[] responseBytes = responseLine.getBuffer();
        int responseLength = responseLine.getLength();
        
        // Find first space (separates version and status code)
        int firstSpaceIndex = -1;
        for (int i = 0; i < responseLength; i++) {
            if (responseBytes[i] == HTTP.SPACE) {
                firstSpaceIndex = i;
                break;
            }
        }
        
        if (firstSpaceIndex == -1) {
            throw new IllegalArgumentException("Invalid response line: no space found after version");
        }
        
        // Find second space (separates status code and status message)
        int secondSpaceIndex = -1;
        for (int i = firstSpaceIndex + 1; i < responseLength; i++) {
            if (responseBytes[i] == HTTP.SPACE) {
                secondSpaceIndex = i;
                break;
            }
        }
        
        if (secondSpaceIndex == -1) {
            throw new IllegalArgumentException("Invalid response line: no space found after status code");
        }
        
        // Extract version, status code, and status message
        String version = new String(responseBytes, 0, firstSpaceIndex);
        String statusCodeStr = new String(responseBytes, firstSpaceIndex + 1, secondSpaceIndex - firstSpaceIndex - 1);
        String statusMessage = new String(responseBytes, secondSpaceIndex + 1, responseLength - secondSpaceIndex - 1);
        
        // Parse status code
        int statusCode;
        try {
            statusCode = Integer.parseInt(statusCodeStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid status code: " + statusCodeStr);
        }

        // Reject malformed header syntax from the upstream response too — see
        // validateHeaderSyntax() for why.
        validateHeaderSyntax();

        // Reject ambiguous Content-Length/Transfer-Encoding framing from the upstream response
        // too — the request side already guards against CL.TE/TE.CL/CL.CL smuggling, but a
        // malformed/ambiguous response can just as easily desync this client's keep-alive
        // connection if left unchecked here.
        validateFraming();

        // Extract connection from headers
        String connection = getHeaderValueAsString(HTTP.HEADER.CONNECTION);

        // Extract content length from headers
        Long contentLength = getHeaderValueAsLong(HTTP.HEADER.CONTENT_LENGTH);

        // Determine body stream type
        HttpStream httpStream = determineResponseBodyStreamType(statusCode, isHeadRequest);

        return new HttpResponse(version, statusCode, statusMessage, connection, contentLength, httpStream, this);
    }
    
    /**
     * Determine response body stream type based on status code and headers
     * @param statusCode HTTP status code
     * @param isHeadRequest true if this is a response to a HEAD request
     * @return BodyStream type
     */
    private HttpStream determineResponseBodyStreamType(int statusCode, boolean isHeadRequest) {
        // Check for WebSocket upgrade (101 Switching Protocols)
        if (statusCode == 101) {
            String upgrade = getHeaderValueAsString(HTTP.HEADER.UPGRADE);
            String connection = getHeaderValueAsString(HTTP.HEADER.CONNECTION);
            if (upgrade != null && connection != null &&
                "websocket".equalsIgnoreCase(upgrade) &&
                containsIgnoreCase(connection, "upgrade")) {
                return HttpStream.WEBSOCKET;
            }
        }

        // Informational (1xx) responses, 204/304, and any response to a HEAD request never have
        // a body, regardless of headers — a HEAD response can carry a Content-Length describing
        // what a GET would return, without actually sending that body.
        if (statusCode < 200 || statusCode == 204 || statusCode == 304 || isHeadRequest) {
            return HttpStream.NONE;
        }

        // Check for Transfer-Encoding: chunked
        String transferEncoding = getHeaderValueAsString(HTTP.HEADER.TRANSFER_ENCODING);
        if (transferEncoding != null && containsIgnoreCase(transferEncoding, "chunked")) {
            return HttpStream.CHUNKED;
        }

        // Check for Content-Length
        Long contentLength = getHeaderValueAsLong(HTTP.HEADER.CONTENT_LENGTH);
        if (contentLength != null && contentLength >= 0) {
            return HttpStream.CONTENT_LENGTH;
        }

        // No Content-Length and no Transfer-Encoding: body is delimited by connection close (RFC 7230 §3.3.3 case 7)
        return HttpStream.UNTIL_CLOSE;
    }

    private static boolean containsIgnoreCase(String text, String search) {
        int tLen = text.length(), sLen = search.length();
        if (sLen == 0) return true;
        if (tLen < sLen) return false;
        outer:
        for (int i = 0, limit = tLen - sLen; i <= limit; i++) {
            for (int j = 0; j < sLen; j++) {
                if (Character.toLowerCase(text.charAt(i + j)) != search.charAt(j)) continue outer;
            }
            return true;
        }
        return false;
    }
}
