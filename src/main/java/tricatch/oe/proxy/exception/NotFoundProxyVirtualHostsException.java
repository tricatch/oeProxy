package tricatch.oe.proxy.exception;

public class NotFoundProxyVirtualHostsException extends ConfigException {

    private final boolean noneConfigured;

    public NotFoundProxyVirtualHostsException(String message) {
        this(message, false);
    }

    private NotFoundProxyVirtualHostsException(String message, boolean noneConfigured) {
        super(message);
        this.noneConfigured = noneConfigured;
    }

    /** For an identified owner that simply has no virtual hosts, as opposed to ones that failed to load. */
    public static NotFoundProxyVirtualHostsException noneConfigured(String message) {
        return new NotFoundProxyVirtualHostsException(message, true);
    }

    public boolean isNoneConfigured() {
        return noneConfigured;
    }
}
