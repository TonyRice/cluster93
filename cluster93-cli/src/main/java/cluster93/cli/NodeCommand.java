package cluster93.cli;

import cluster93.tls.ClusterCa;
import cluster93.tls.ClusterInit;
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Command(name = "node", description = "Add or remove core nodes (operator only).", subcommands = {NodeCommand.Invite.class, NodeCommand.Revoke.class})
final class NodeCommand extends Base {

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    @Command(name = "invite", description = "Sign a certificate for a node with ca.key, here on your machine, and print the one line "
            + "to start it with. Also renews a node's certificate.")
    static final class Invite extends Base {
        @Parameters(paramLabel = "ID", description = "The node's id (C93_NODE_ID).")
        String nodeId;

        @Option(names = "--host", paramLabel = "ADDRESS", description = "An address other nodes reach it at (repeatable).")
        List<String> hosts = new ArrayList<>();

        @Option(names = "--ca", defaultValue = "./cluster-tls/ca.key", paramLabel = "FILE", description = "The CA key (ca.crt beside it).")
        Path ca;

        @Option(names = "--key", paramLabel = "PUBLIC_KEY", description = "The public key from `cluster93-server --prepare` on the node: "
                + "prints a certificate-only line, and the node's private key never leaves it.")
        String publicKey;

        @Override
        int execute() {
            if (!Files.isRegularFile(ca)) {
                throw new CliException("No CA key at " + ca + " (use --ca; it was written by `c93 cluster init`)", 2);
            }
            String line;
            try {
                line = ClusterInit.invite(ClusterCa.load(ca), nodeId, hosts, publicKey);
            } catch (IllegalArgumentException e) {
                throw new CliException(e.getMessage(), 2);
            }
            if (json()) {
                out().println(Json.pretty(Json.object().put("nodeId", nodeId).put("join", line)));
                return 0;
            }
            err().println(publicKey == null
                    ? "Start " + nodeId + " with this line. It holds " + nodeId + "'s private key: treat it like a password (it can't create other nodes)."
                    : "Start " + nodeId + " with this line (no secrets in it; the key stayed on the node):");
            out().println("C93_JOIN=" + line);
            return 0;
        }
    }

    @Command(name = "revoke", description = "Revoke a node: every node refuses it from now on, and it's forced out of the cluster.")
    static final class Revoke extends Base {
        @Parameters(paramLabel = "ID")
        String nodeId;

        @Override
        int execute() {
            JsonNode result = api().post("/v1/cluster/nodes/" + Api.segment(nodeId) + "/revoke", null);
            if (!printedJson(result)) {
                out().println("Revoked " + nodeId + (result.path("stopped").asBoolean() ? "; it was in the cluster and has been forced out." : "."));
                out().println("To let it back in, invite it again: c93 node invite " + nodeId + " --ca ./cluster-tls/ca.key");
            }
            return 0;
        }
    }
}
