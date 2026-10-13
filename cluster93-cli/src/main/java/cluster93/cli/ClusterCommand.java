package cluster93.cli;

import cluster93.tls.ClusterInit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Command(name = "cluster", description = "Create a cluster's certificates (operator only).", subcommands = {ClusterCommand.Init.class})
final class ClusterCommand extends Base {

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    @Command(name = "init", description = "Create the cluster's CA and a certificate for each first node. ca.key stays with you: "
            + "the cluster never stores it.")
    static final class Init extends Base {
        @Option(names = "--nodes", required = true, split = ",", paramLabel = "ID,ID", description = "The first nodes' ids, e.g. node1,node2,node3.")
        List<String> nodes;

        @Option(names = "--host", paramLabel = "ID=ADDRESS", description = "An extra address for a node's certificate, e.g. node1=10.0.0.11 (repeatable).")
        List<String> hosts = new ArrayList<>();

        @Option(names = "--name", defaultValue = "c93", description = "A name for the CA.")
        String name;

        @Option(names = "--out", defaultValue = "./cluster-tls", paramLabel = "DIR")
        Path out;

        @Override
        int execute() {
            Map<String, List<String>> extra = new HashMap<>();
            for (String pair : hosts) {
                String[] parts = pair.split("=", 2);
                if (parts.length != 2) {
                    throw new CliException("--host takes ID=ADDRESS, e.g. node1=10.0.0.11", 2);
                }
                extra.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(parts[1]);
            }
            ClusterInit.Result result;
            try {
                result = ClusterInit.init(name, nodes, extra, out);
            } catch (IllegalStateException | IllegalArgumentException e) {
                throw new CliException(e.getMessage(), 1);
            }
            out().println("Cluster CA " + result.ca().fingerprint());
            out().println();
            out().println("  " + out.resolve("ca.key") + "   keep it safe and back it up: it's how nodes are added. The cluster never stores it.");
            out().println("  " + out.resolve("ca.crt"));
            for (String id : nodes) {
                out().println("  " + out.resolve(id + ".join") + "   start " + id + " with C93_JOIN=$(cat " + id + ".join)");
            }
            return 0;
        }
    }
}
