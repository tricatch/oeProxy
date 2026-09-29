package tricatch.oe.proxy.spi;

/**
 * The client-provided values an {@link ErrorPageRenderer} needs to pick a locale (or any other
 * per-client rendering choice) without this library depending on any particular i18n framework.
 */
public record ClientHints(String cookie, String acceptLanguage) {
    public static final ClientHints NONE = new ClientHints(null, null);
}
