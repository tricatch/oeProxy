package tricatch.oe.proxy.exception;

import java.io.IOException;

public class NotFoundVhostException extends IOException {

    private final String requestHost;
    private final String requestPath;

    public NotFoundVhostException(String requestHost, String requestPath, String message) {
        super(message);
        this.requestHost = requestHost;
        this.requestPath = requestPath != null ? requestPath : "";
    }

    public String getRequestHost() { return requestHost; }
    public String getRequestPath() { return requestPath; }
}
