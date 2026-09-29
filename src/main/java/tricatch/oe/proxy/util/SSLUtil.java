package tricatch.oe.proxy.util;

import io.github.tricatch.gotpache.cert.KeyTool;
import tricatch.oe.proxy.cert.MultiDomainCertKeyManager;
import tricatch.oe.proxy.cfg.Config;
import tricatch.oe.proxy.cfg.attr.Ca;
import tricatch.oe.proxy.exception.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

public class SSLUtil {

    private static final Logger logger = LoggerFactory.getLogger(SSLUtil.class);

    /**
     * Initialize SSL context with CA certificate and private key
     * @param config configuration
     * @return Initialized SSL context
     * @throws ConfigException if SSL initialization fails
     */
    public static SSLContext initializeSSLContext(Config config) throws ConfigException {
        try {
            logger.info("Initializing SSL context...");
            
            Ca ca = config.getCa();
            if (ca == null) {
                throw new ConfigException("CA configuration is missing");
            }

            // Validate CA configuration
            if (ca.getCert() == null) {
                throw new ConfigException("CA certificate file path is null");
            }
            if (ca.getCert().trim().isEmpty()) {
                throw new ConfigException("CA certificate file path is empty");
            }
            if (ca.getPriKey() == null) {
                throw new ConfigException("CA private key file path is null");
            }
            if (ca.getPriKey().trim().isEmpty()) {
                throw new ConfigException("CA private key file path is empty");
            }

            KeyTool keyTool = new KeyTool();
            logger.info("Reading CA certificate: {}", ca.getCert());
            Path certPath = Path.of(ca.getCert().trim());
            X509Certificate rootCertificate = keyTool.readCertificate(
                    certPath.getParent().toString(), certPath.getFileName().toString());

            logger.info("Reading CA private key: {}", ca.getPriKey());
            Path keyPath = Path.of(ca.getPriKey().trim());
            PrivateKey rootPrivateKey;
            if (ca.getPriPwd() != null && !ca.getPriPwd().trim().isEmpty()) {
                rootPrivateKey = keyTool.readPrivateKey(
                        keyPath.getParent().toString(), keyPath.getFileName().toString(), ca.getPriPwd().trim());
            } else {
                rootPrivateKey = keyTool.readPrivateKey(
                        keyPath.getParent().toString(), keyPath.getFileName().toString());
            }

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null); // Initialize empty keystore
            
            KeyManager[] kms = new KeyManager[]{
                    new MultiDomainCertKeyManager(rootCertificate, rootPrivateKey)
            };

            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(ks);
            
            TrustManager[] tms = tmf.getTrustManagers();

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kms, tms, null);

            logger.info("SSL context initialized successfully with CA: {}", ca.getCert());

            return sslContext;

        } catch (Exception e) {
            throw new ConfigException("SSL config error - " + e.getMessage(), e);
        }
    }
}
