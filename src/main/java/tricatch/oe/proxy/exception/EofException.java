package tricatch.oe.proxy.exception;

import java.io.IOException;

public class EofException extends IOException {
    public EofException(String message){
        super(message);
    }
}
