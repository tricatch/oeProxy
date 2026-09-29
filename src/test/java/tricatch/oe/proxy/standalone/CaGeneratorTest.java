package tricatch.oe.proxy.standalone;

import io.github.tricatch.gotpache.cert.KeyTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaGeneratorTest {

    @Test
    void generate_writesReadableCertAndKey(@TempDir Path dir) throws Exception {
        CaGenerator.Result result = new CaGenerator().generate(dir, "Test CA", false);

        assertThat(result.certPath()).exists();
        assertThat(result.keyPath()).exists();
        assertThat(result.certPath()).isEqualTo(dir.resolve("ca.cer").toAbsolutePath());
        assertThat(result.keyPath()).isEqualTo(dir.resolve("ca.pfx").toAbsolutePath());

        KeyTool keyTool = new KeyTool();
        var cert = keyTool.readCertificate(dir.toString(), "ca.cer");
        var key = keyTool.readPrivateKey(dir.toString(), "ca.pfx");

        assertThat(cert.getSubjectX500Principal().getName()).contains("Test CA");
        assertThat(key).isNotNull();
    }

    @Test
    void generate_usesDefaultName_whenNoneGiven(@TempDir Path dir) throws Exception {
        new CaGenerator().generate(dir, null, false);

        var cert = new KeyTool().readCertificate(dir.toString(), "ca.cer");
        assertThat(cert.getSubjectX500Principal().getName()).contains(CaGenerator.DEFAULT_NAME);
    }

    @Test
    void secondRun_withoutForce_fails(@TempDir Path dir) throws Exception {
        new CaGenerator().generate(dir, "Test CA", false);

        assertThatThrownBy(() -> new CaGenerator().generate(dir, "Test CA", false))
                .isInstanceOf(CaGenerator.AlreadyExistsException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void secondRun_withForce_succeeds(@TempDir Path dir) throws Exception {
        new CaGenerator().generate(dir, "First CA", false);
        CaGenerator.Result second = new CaGenerator().generate(dir, "Second CA", true);

        var cert = new KeyTool().readCertificate(dir.toString(), "ca.cer");
        assertThat(cert.getSubjectX500Principal().getName()).contains("Second CA");
        assertThat(second.certPath()).exists();
    }

    @Test
    void generate_createsOutDir_whenMissing(@TempDir Path parent) throws Exception {
        Path outDir = parent.resolve("nested").resolve("ca-dir");
        assertThat(Files.exists(outDir)).isFalse();

        CaGenerator.Result result = new CaGenerator().generate(outDir, "Nested CA", false);

        assertThat(Files.exists(outDir)).isTrue();
        assertThat(result.certPath()).exists();
    }
}
