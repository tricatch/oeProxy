package tricatch.oe.proxy.standalone;

import io.github.tricatch.gotpache.cert.CertificateKeyPair;
import io.github.tricatch.gotpache.cert.KeyTool;
import io.github.tricatch.gotpache.cert.RootCertificateCreator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Generates the self-signed root CA (certificate + unencrypted private key) a standalone oe-proxy
 * deployment signs its per-domain SSL certs with. Uses the exact same gotpache-keytool API an
 * embedding application's own CA generation can go through -
 * a plain RootCertificateCreator + KeyTool.writeCertificate/writePrivateKey pair - so the files this
 * CLI produces are byte-for-byte the same shape an embedding application itself writes.
 */
public class CaGenerator {

    public static final String DEFAULT_NAME = "oeProxy Local CA";
    static final String CERT_FILE = "ca.cer";
    static final String KEY_FILE = "ca.pfx";

    /** Absolute paths of the certificate and private-key files just written. */
    public record Result(Path certPath, Path keyPath) {}

    /** The requested outDir already holds ca.cer or ca.pfx, and --force was not given. */
    public static class AlreadyExistsException extends Exception {
        public AlreadyExistsException(String message) {
            super(message);
        }
    }

    /**
     * Writes ca.cer/ca.pfx into outDir (created if missing). Refuses if either file already
     * exists unless force is true.
     */
    public Result generate(Path outDir, String name, boolean force) throws AlreadyExistsException, IOException {
        Files.createDirectories(outDir);

        Path certPath = outDir.resolve(CERT_FILE);
        Path keyPath = outDir.resolve(KEY_FILE);

        if (!force && (Files.exists(certPath) || Files.exists(keyPath))) {
            throw new AlreadyExistsException(
                    "CA already exists in " + outDir.toAbsolutePath() + " (use --force to overwrite)");
        }

        String caName = (name == null || name.isBlank()) ? DEFAULT_NAME : name;

        CertificateKeyPair rootCert;
        try {
            rootCert = new RootCertificateCreator().generateRootCertificate(caName);
        } catch (Exception e) {
            // GotpacheCertException wraps checked crypto-provider failures; surface as IOException
            // so callers only need to handle the two exception types documented above.
            throw new IOException("failed to generate root certificate: " + e.getMessage(), e);
        }

        KeyTool keyTool = new KeyTool();
        keyTool.writeCertificate(rootCert.getCertificate(), outDir.toString(), CERT_FILE);
        keyTool.writePrivateKey(rootCert.getPrivateKey(), outDir.toString(), KEY_FILE);

        restrictToOwner(keyPath);

        return new Result(certPath.toAbsolutePath(), keyPath.toAbsolutePath());
    }

    // Mirrors tricatch.oe.hub.config.AppHome.restrictToOwner: best-effort owner-only permissions
    // on POSIX filesystems. Silently skipped on Windows (NTFS ACLs already default to the current
    // user there) and on any other filesystem without POSIX permission support.
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }
}
