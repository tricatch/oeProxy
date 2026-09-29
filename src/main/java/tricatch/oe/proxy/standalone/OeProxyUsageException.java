package tricatch.oe.proxy.standalone;

/**
 * No args, an unknown subcommand, -h/--help, or a malformed CLI argument (e.g. an unknown
 * option). OeProxyMain maps this to usage-on-stderr + exit code 2, per the CLI contract.
 */
public class OeProxyUsageException extends Exception {
    public OeProxyUsageException(String message) {
        super(message);
    }
}
