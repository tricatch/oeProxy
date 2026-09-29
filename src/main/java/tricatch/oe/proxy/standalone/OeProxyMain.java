package tricatch.oe.proxy.standalone;

import java.nio.file.Path;

/**
 * Standalone CLI entry point for running oe-proxy's reverse-proxy core without embedding it in
 * another application (see the "standalone" README section).
 *
 * <pre>
 * oe-proxy ca  [outDir] [--name "&lt;CA common name&gt;"] [--force]
 * oe-proxy run &lt;routes.yml&gt; [--ca-dir &lt;dir&gt;] [--allow-external-upstream] [--monitor[=basic|headers|full]]
 * </pre>
 */
public class OeProxyMain {

    private static final String USAGE = """
            Usage:
              oe-proxy ca  [outDir] [--name "<CA common name>"] [--force]
              oe-proxy run <routes.yml> [--ca-dir <dir>] [--allow-external-upstream] [--monitor[=basic|headers|full]]

            outDir/ca-dir default to <user.home>/oeProxy/root-ca.
            """;

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    // Package-visible and returning an int (rather than calling System.exit directly) so tests
    // can drive the whole CLI without killing the test JVM.
    static int run(String[] args) {
        // Per the CLI contract: no args, an unknown subcommand, or an explicit -h/--help request
        // all print usage to stderr and exit 2 (not 0) - -h/--help is not "successful help", it's
        // simply another case of "not a valid invocation to run".
        if (args.length == 0 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            System.err.print(USAGE);
            return 2;
        }

        String subcommand = args[0];
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);

        try {
            switch (subcommand) {
                case "ca" -> runCa(rest);
                case "run" -> runRun(rest);
                default -> {
                    System.err.println("Unknown subcommand: " + subcommand);
                    System.err.print(USAGE);
                    return 2;
                }
            }
            return 0;
        } catch (OeProxyUsageException e) {
            System.err.println("oe-proxy: " + e.getMessage());
            System.err.print(USAGE);
            return 2;
        } catch (Exception e) {
            if (Boolean.getBoolean("oe.proxy.debug")) {
                e.printStackTrace();
            } else {
                System.err.println("oe-proxy: " + e.getMessage());
            }
            return 1;
        }
    }

    private static void runCa(String[] args) throws OeProxyUsageException, CaGenerator.AlreadyExistsException, java.io.IOException {
        Path outDir = StandaloneOptions.defaultCaDir();
        String name = CaGenerator.DEFAULT_NAME;
        boolean force = false;

        int i = 0;
        if (i < args.length && !args[i].startsWith("--")) {
            outDir = Path.of(args[i]);
            i++;
        }
        for (; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--name" -> {
                    if (i + 1 >= args.length) throw new OeProxyUsageException("--name requires a value");
                    name = args[++i];
                }
                case "--force" -> force = true;
                default -> throw new OeProxyUsageException("ca: unknown option " + arg);
            }
        }

        CaGenerator.Result result = new CaGenerator().generate(outDir, name, force);

        System.out.println("CA certificate: " + result.certPath());
        System.out.println("CA private key: " + result.keyPath());
        System.out.println();
        System.out.println("Next steps:");
        System.out.println("  1. Import " + result.certPath() + " into your OS/browser trust store.");
        System.out.println("  2. Start the proxy with this CA:");
        System.out.println("       oe-proxy run <routes.yml> --ca-dir " + result.certPath().getParent());
        System.out.println("     (omit --ca-dir if it is the default: " + StandaloneOptions.defaultCaDir() + ")");
    }

    private static void runRun(String[] args) throws OeProxyUsageException, OeProxyConfigException {
        StandaloneOptions options = StandaloneOptions.parse(args);
        new StandaloneServer().start(options);
    }
}
