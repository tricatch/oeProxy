package tricatch.oe.proxy.standalone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Parsed and validated options for the `run` subcommand:
 * <pre>oe-proxy run &lt;routes.yml&gt; [--ca-dir &lt;dir&gt;] [--allow-external-upstream] [--monitor[=basic|headers|full]]</pre>
 * routes.yml is the same {@code virtual:} routing document embedding-application users write - see
 * tricatch.oe.proxy.cfg.VirtualHost. Its content is read here (and lightly pre-checked, so a
 * malformed file fails with a clear message instead of an NPE deep inside
 * ReverseProxyServer.setVirtualHosts) but is otherwise handed to that method unmodified - the
 * real, tag-safe parse happens there exactly once.
 */
public class StandaloneOptions {

    public static final int CONNECT_TIMEOUT_MS = 3000;
    public static final int READ_TIMEOUT_MS = 30000;

    private static final Logger logger = LoggerFactory.getLogger(StandaloneOptions.class);

    private final Path routesFile;
    private final String routesYaml;
    private final Path caDir;
    private final boolean allowExternalUpstream;
    private final MonitorLevel monitorLevel;

    private StandaloneOptions(Path routesFile, String routesYaml, Path caDir, boolean allowExternalUpstream, MonitorLevel monitorLevel) {
        this.routesFile = routesFile;
        this.routesYaml = routesYaml;
        this.caDir = caDir;
        this.allowExternalUpstream = allowExternalUpstream;
        this.monitorLevel = monitorLevel;
    }

    public Path getRoutesFile() {
        return routesFile;
    }

    public String getRoutesYaml() {
        return routesYaml;
    }

    public Path getCaDir() {
        return caDir;
    }

    public boolean isAllowExternalUpstream() {
        return allowExternalUpstream;
    }

    public MonitorLevel getMonitorLevel() {
        return monitorLevel;
    }

    /** {@code <user.home>/oeProxy/root-ca} - mirrors the embedding application's own root-ca layout. */
    public static Path defaultCaDir() {
        return Path.of(System.getProperty("user.home"), "oeProxy", "root-ca");
    }

    /** Parses and validates the args following the leading "run" token. */
    public static StandaloneOptions parse(String[] args) throws OeProxyUsageException, OeProxyConfigException {
        if (args.length == 0 || args[0].startsWith("--")) {
            throw new OeProxyUsageException("run: routes.yml is required");
        }

        Path routesFile = Path.of(args[0]);
        Path caDir = defaultCaDir();
        boolean allowExternalUpstream = false;
        MonitorLevel monitorLevel = MonitorLevel.OFF;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--ca-dir" -> {
                    if (i + 1 >= args.length) throw new OeProxyUsageException("--ca-dir requires a value");
                    caDir = Path.of(args[++i]);
                }
                case "--allow-external-upstream" -> allowExternalUpstream = true;
                case "--monitor" -> monitorLevel = MonitorLevel.BASIC;
                default -> {
                    if (arg.startsWith("--monitor=")) {
                        monitorLevel = parseMonitorLevel(arg.substring("--monitor=".length()));
                    } else {
                        throw new OeProxyUsageException("run: unknown option " + arg);
                    }
                }
            }
        }

        if (!Files.exists(routesFile)) {
            throw new OeProxyConfigException("routes file not found: " + routesFile.toAbsolutePath());
        }

        String routesYaml;
        try {
            routesYaml = Files.readString(routesFile);
        } catch (IOException e) {
            throw new OeProxyConfigException("failed to read " + routesFile.toAbsolutePath() + ": " + e.getMessage());
        }

        precheckRoutes(routesFile, routesYaml);

        return new StandaloneOptions(routesFile, routesYaml, caDir, allowExternalUpstream, monitorLevel);
    }

    private static MonitorLevel parseMonitorLevel(String value) throws OeProxyUsageException {
        return switch (value) {
            case "basic" -> MonitorLevel.BASIC;
            case "headers" -> MonitorLevel.HEADERS;
            case "full" -> MonitorLevel.FULL;
            default -> throw new OeProxyUsageException("--monitor must be basic, headers or full");
        };
    }

    // Light structural check only: a Map with a non-empty 'virtual' list. The real vhost schema
    // (domain/location/host/path/header shapes) is validated exactly once, inside
    // ReverseProxyServer.setVirtualHosts, which is the single source of truth for it.
    @SuppressWarnings("unchecked")
    private static void precheckRoutes(Path routesFile, String routesYaml) throws OeProxyConfigException {
        // routes.yml is user-authored, so it gets the same SafeConstructor/no-explicit-tags
        // treatment ReverseProxyServer.mergeVhostYaml uses elsewhere for the same reason.
        var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object parsed;
        try {
            parsed = yaml.load(routesYaml);
        } catch (Exception e) {
            throw new OeProxyConfigException(routesFile.getFileName() + ": invalid YAML - " + e.getMessage());
        }

        if (!(parsed instanceof Map)) {
            throw new OeProxyConfigException(routesFile.getFileName() + ": 'virtual' must be a non-empty list");
        }
        Map<String, Object> map = (Map<String, Object>) parsed;

        for (String key : map.keySet()) {
            if (!"virtual".equals(key)) {
                logger.warn("{}: unknown top-level key '{}' (ignored)", routesFile.getFileName(), key);
            }
        }

        Object virtual = map.get("virtual");
        if (!(virtual instanceof List<?> list) || list.isEmpty()) {
            throw new OeProxyConfigException(routesFile.getFileName() + ": 'virtual' must be a non-empty list");
        }
    }
}
