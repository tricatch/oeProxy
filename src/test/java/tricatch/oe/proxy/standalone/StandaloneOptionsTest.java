package tricatch.oe.proxy.standalone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandaloneOptionsTest {

    private Path writeRoutes(Path dir, String content) throws IOException {
        Path file = dir.resolve("routes.yml");
        Files.writeString(file, content);
        return file;
    }

    private static final String VALID_ROUTES = """
            virtual:
              - domain: app.test
                location:
                  - host: http://127.0.0.1:8080
                    path: [ /** ]
            """;

    @Test
    void defaults_areApplied(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, VALID_ROUTES);

        StandaloneOptions options = StandaloneOptions.parse(new String[]{routes.toString()});

        assertThat(options.getCaDir()).isEqualTo(StandaloneOptions.defaultCaDir());
        assertThat(options.isAllowExternalUpstream()).isFalse();
        assertThat(options.getRoutesFile()).isEqualTo(routes);
        assertThat(options.getRoutesYaml()).isEqualTo(VALID_ROUTES);
    }

    @Test
    void explicitOptions_overrideDefaults(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, VALID_ROUTES);
        Path caDir = dir.resolve("myca");

        StandaloneOptions options = StandaloneOptions.parse(new String[]{
                routes.toString(), "--ca-dir", caDir.toString(), "--allow-external-upstream"
        });

        assertThat(options.getCaDir()).isEqualTo(caDir);
        assertThat(options.isAllowExternalUpstream()).isTrue();
    }

    @Test
    void missingRoutesFile_isRejected() {
        assertThatThrownBy(() -> StandaloneOptions.parse(new String[]{"/no/such/routes.yml"}))
                .isInstanceOf(OeProxyConfigException.class)
                .hasMessageContaining("routes file not found");
    }

    @Test
    void noArgs_isRejected() {
        assertThatThrownBy(() -> StandaloneOptions.parse(new String[]{}))
                .isInstanceOf(OeProxyUsageException.class)
                .hasMessageContaining("routes.yml is required");
    }

    @Test
    void emptyVirtualList_isRejected(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, "virtual: []\n");

        assertThatThrownBy(() -> StandaloneOptions.parse(new String[]{routes.toString()}))
                .isInstanceOf(OeProxyConfigException.class)
                .hasMessageContaining("'virtual' must be a non-empty list");
    }

    @Test
    void missingVirtualKey_isRejected(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, "notvirtual: []\n");

        assertThatThrownBy(() -> StandaloneOptions.parse(new String[]{routes.toString()}))
                .isInstanceOf(OeProxyConfigException.class)
                .hasMessageContaining("'virtual' must be a non-empty list");
    }

    @Test
    void nonMapRoutesContent_isRejected(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, "- just\n- a\n- list\n");

        assertThatThrownBy(() -> StandaloneOptions.parse(new String[]{routes.toString()}))
                .isInstanceOf(OeProxyConfigException.class)
                .hasMessageContaining("'virtual' must be a non-empty list");
    }

    @Test
    void unknownTopLevelKey_onlyWarns(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, VALID_ROUTES + "extraKey: 1\n");

        StandaloneOptions options = StandaloneOptions.parse(new String[]{routes.toString()});

        assertThat(options.getRoutesYaml()).contains("extraKey");
    }

    @Test
    void portOption_isRejectedAsUnknown(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, VALID_ROUTES);

        assertThatThrownBy(() -> StandaloneOptions.parse(new String[]{routes.toString(), "--port", "8443"}))
                .isInstanceOf(OeProxyUsageException.class)
                .hasMessageContaining("unknown option");
    }

    @Test
    void unknownOption_isRejected(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, VALID_ROUTES);

        assertThatThrownBy(() -> StandaloneOptions.parse(new String[]{routes.toString(), "--bogus"}))
                .isInstanceOf(OeProxyUsageException.class)
                .hasMessageContaining("unknown option");
    }

    @Test
    void monitorOption_defaultsOff(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, VALID_ROUTES);

        assertThat(StandaloneOptions.parse(new String[]{routes.toString()}).getMonitorLevel()).isEqualTo(MonitorLevel.OFF);
    }

    @Test
    void monitorOption_acceptsEachForm(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, VALID_ROUTES);

        assertThat(StandaloneOptions.parse(new String[]{routes.toString(), "--monitor"}).getMonitorLevel())
                .isEqualTo(MonitorLevel.BASIC);
        assertThat(StandaloneOptions.parse(new String[]{routes.toString(), "--monitor=basic"}).getMonitorLevel())
                .isEqualTo(MonitorLevel.BASIC);
        assertThat(StandaloneOptions.parse(new String[]{routes.toString(), "--monitor=headers"}).getMonitorLevel())
                .isEqualTo(MonitorLevel.HEADERS);
        assertThat(StandaloneOptions.parse(new String[]{routes.toString(), "--monitor=full"}).getMonitorLevel())
                .isEqualTo(MonitorLevel.FULL);
    }

    @Test
    void monitorOption_invalidValueIsRejected(@TempDir Path dir) throws Exception {
        Path routes = writeRoutes(dir, VALID_ROUTES);

        assertThatThrownBy(() -> StandaloneOptions.parse(new String[]{routes.toString(), "--monitor=verbose"}))
                .isInstanceOf(OeProxyUsageException.class)
                .hasMessage("--monitor must be basic, headers or full");
        assertThatThrownBy(() -> StandaloneOptions.parse(new String[]{routes.toString(), "--monitor="}))
                .isInstanceOf(OeProxyUsageException.class)
                .hasMessage("--monitor must be basic, headers or full");
    }
}
