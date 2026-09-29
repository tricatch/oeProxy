package tricatch.oe.proxy.exception;

/**
 * A virtual host config was refused because a backend resolves outside the internal network while
 * "Allow internal-network backends only" is on. Its own type so the oeProxy page can tell the user
 * that reason apart from a malformed config.
 */
public class UpstreamNotInternalException extends IllegalArgumentException {

    public UpstreamNotInternalException(String message) {
        super(message);
    }
}
