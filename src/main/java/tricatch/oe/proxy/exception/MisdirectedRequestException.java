package tricatch.oe.proxy.exception;

import java.io.IOException;

/** A request whose Host is not the name its TLS connection was opened for (HTTP 421). */
public class MisdirectedRequestException extends IOException {

    public MisdirectedRequestException(String message) {
        super(message);
    }
}
