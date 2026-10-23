package cluster93.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/**
 * {@code c93 dev}: the checkout stack (D-190). One persistent data directory, a fixed dev admin token, a seeded
 * tenant/operator/console, and no service unit. {@code c93 local} remains an alias for one release.
 */
@Command(name = "dev", description = "Build and run a throwaway local Cluster93 stack from this checkout.",
        subcommands = {DevCommand.Up.class, DevCommand.Status.class, DevCommand.Logs.class, DevCommand.Down.class,
                DevCommand.Reset.class, DevCommand.Build.class, DevCommand.Seed.class, DevCommand.TestExamples.class})
final class DevCommand extends Base {

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    private static DevService service(Base base) {
        return DevService.open(base.io());
    }

    @Command(name = "up", description = "Build if needed, start the stack, seed it, and make the dev profile active.")
    static final class Up extends Base {

        @Option(names = "--data-dir", paramLabel = "DIR", description = "The data directory (default: ~/.cluster93/dev-data).")
        Path dataDir;

        @Option(names = "--engine", paramLabel = "javet|graaljs", description = "The default worker engine.")
        String engine;

        @Option(names = "--http-port", paramLabel = "PORT", description = "The API port (default 9393).")
        int httpPort;

        @Option(names = "--ingress-port", paramLabel = "PORT", description = "The ingress/console port (default 9394).")
        int ingressPort;

        @Option(names = "--metrics-port", paramLabel = "PORT", description = "The metrics port (default 9395).")
        int metricsPort;

        @Option(names = "--internal-port", paramLabel = "PORT", description = "The cluster-internal port (default 9396).")
        int internalPort;

        @Option(names = "--cluster-port", paramLabel = "PORT", description = "Ignite discovery/communication base port (default 5701).")
        int clusterPort;

        @Option(names = "--no-seed", description = "Start it without creating the tenant/operator/console.")
        boolean noSeed;

        @Override
        int execute() throws Exception {
            DevService service = service(this);
            DevService.State existing = service.state();
            Path data = dataDir != null ? dataDir.toAbsolutePath().normalize() : existing.dataDir();
            String workerEngine = engine == null || engine.isBlank() ? existing.engine() : engine;
            if (!"javet".equals(workerEngine) && !"graaljs".equals(workerEngine)) {
                throw new CliException("--engine must be javet or graaljs", 2);
            }
            DevService.State state = new DevService.State(data,
                    httpPort > 0 ? httpPort : existing.httpPort(),
                    ingressPort > 0 ? ingressPort : existing.ingressPort(),
                    metricsPort > 0 ? metricsPort : existing.metricsPort(),
                    internalPort > 0 ? internalPort : existing.internalPort(),
                    clusterPort > 0 ? clusterPort : existing.clusterPort(),
                    workerEngine);
            return service.up(state, noSeed, json());
        }
    }

    @Command(name = "status", description = "Show the dev stack's process, health, nodes and data directory.")
    static final class Status extends Base {

        @Override
        int execute() {
            DevService service = service(this);
            return service.status(service.state(), json());
        }
    }

    @Command(name = "logs", description = "Show the dev node's server log.")
    static final class Logs extends Base {

        @Option(names = {"-f", "--follow"}, description = "Keep printing new lines.")
        boolean follow;

        @Option(names = {"-n", "--lines"}, defaultValue = "50", paramLabel = "N", description = "How many lines to show first.")
        int lines;

        @Override
        int execute() throws Exception {
            DevService service = service(this);
            return service.logs(service.state(), lines, follow);
        }
    }

    @Command(name = "down", description = "Stop the dev stack; its data is kept.")
    static final class Down extends Base {

        @Override
        int execute() {
            DevService service = service(this);
            return service.down(service.state(), json());
        }
    }

    @Command(name = "reset", description = "Stop the dev stack and delete its data directory.")
    static final class Reset extends Base {

        @Option(names = "--yes", description = "Delete without asking.")
        boolean yes;

        @Override
        int execute() throws Exception {
            DevService service = service(this);
            return service.reset(service.state(), yes, json());
        }
    }

    @Command(name = "build", description = "Rebuild the server, and the worker binaries if they are missing.")
    static final class Build extends Base {

        @Override
        int execute() throws Exception {
            return service(this).build(json());
        }
    }

    @Command(name = "seed", description = "Create the system/dev tenants and the admin operator; save the dev login.")
    static final class Seed extends Base {

        @Override
        int execute() throws Exception {
            DevService service = service(this);
            return service.seed(service.state(), json());
        }
    }

    @Command(name = "test-examples", description = "Run the examples in examples/js against the dev stack.")
    static final class TestExamples extends Base {

        @Override
        int execute() {
            DevService service = service(this);
            return service.testExamples(service.state());
        }
    }
}
