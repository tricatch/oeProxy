package tricatch.oe.proxy.standalone;

/**
 * A well-formed CLI invocation that fails for an operational reason (missing routes file, invalid
 * routes content, missing CA files, port already in use, ...). OeProxyMain maps this to a one-line
 * stderr message (no stack trace, unless -Doe.proxy.debug=true) + exit code 1.
 */
public class OeProxyConfigException extends Exception {
    public OeProxyConfigException(String message) {
        super(message);
    }

    public OeProxyConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
