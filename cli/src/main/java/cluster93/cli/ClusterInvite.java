package cluster93.cli;

import cluster93.tls.ClusterCa;
import cluster93.tls.ClusterInit;
import cluster93.tls.NodeBundle;
import cluster93.tls.Pem;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Signing a node certificate and recording it in the cluster inventory, shared by `cluster invite` and its alias. */
final class ClusterInvite {

    private ClusterInvite() {
    }

    static int run(Base base, String nodeId, List<String> hosts, Path ca, String publicKey, Path file) {
        if (!Files.isRegularFile(ca)) {
            throw new CliException("No CA key at " + ca + " (use --ca; it was written by `c93 cluster init`)", 2);
        }
        String line;
        NodeBundle.Contents contents;
        try {
            line = ClusterInit.invite(ClusterCa.load(ca), nodeId, hosts, publicKey);
            contents = NodeBundle.decode(line);
        } catch (IllegalArgumentException e) {
            throw new CliException(e.getMessage(), 2);
        }

        Path inventoryPath = file != null ? ClusterFile.resolve(file)
                : ca.toAbsolutePath().normalize().getParent().resolve(ClusterFile.FILE);
        ClusterFile cluster = ClusterFile.load(inventoryPath);
        if (cluster == null) {
            cluster = ClusterFile.create(inventoryPath, null, null);
        }
        Path certificate = cluster.dir().resolve(nodeId + ".crt");
        Path join = cluster.dir().resolve(nodeId + ".join");
        try {
            Files.writeString(certificate, Pem.certificate(contents.certificate()));
        } catch (IOException e) {
            throw new CliException("Can't write " + certificate + ": " + e.getMessage());
        }
        ProfileStore.writeAtomically(join, line + System.lineSeparator());
        List<String> addresses = new ArrayList<>();
        addresses.add(nodeId);
        addresses.addAll(hosts);
        cluster.putNode(nodeId, addresses, certificate, contents.certificate().getNotAfter().toInstant(), join);
        cluster.save();

        if (base.json()) {
            Output.json(base.out(), Json.object().put("nodeId", nodeId).put("join", line)
                    .put("inventory", cluster.file().toString()));
            return 0;
        }
        base.err().println(publicKey == null
                ? "Start " + nodeId + " with this line. It holds " + nodeId + "'s private key: treat it like a password "
                + "(it can't create other nodes)."
                : "Start " + nodeId + " with this line (no secrets in it; the key stayed on the node):");
        base.out().println("C93_JOIN=" + line);
        base.err().println("Recorded in " + cluster.file() + " (" + nodeId + ".crt, " + nodeId + ".join).");
        return 0;
    }
}

/** The shared `cluster invite` / `node invite` command fields. */
abstract class InviteSupport extends Base {

    @Parameters(paramLabel = "ID", description = "The node's id (C93_NODE_ID).")
    String nodeId;

    @Option(names = "--host", paramLabel = "ADDRESS", description = "An address other nodes reach it at (repeatable).")
    List<String> hosts = new ArrayList<>();

    @Option(names = "--ca", defaultValue = "./cluster-tls/ca.key", paramLabel = "FILE", description = "The CA key (ca.crt beside it).")
    Path ca;

    @Option(names = "--key", paramLabel = "PUBLIC_KEY", description = "The public key from `cluster93-server --prepare` on the node: "
            + "prints a certificate-only line, and the node's private key never leaves it.")
    String publicKey;

    @Option(names = "--file", paramLabel = "FILE", description = "The cluster inventory; defaults to cluster.json beside the CA.")
    Path file;
}
