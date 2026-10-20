package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

/** Revoking a node through the admin API and marking it in the inventory, shared by both command locations. */
final class ClusterRevoke {

    private ClusterRevoke() {
    }

    static int run(Base base, String nodeId, Path file) {
        JsonNode result = base.api().post("/v1/cluster/nodes/" + Api.segment(nodeId) + "/revoke", null);
        Path inventory = file != null ? ClusterFile.resolve(file) : ClusterCommand.inventory(base, null);
        ClusterFile cluster = ClusterFile.load(inventory);
        if (cluster != null) {
            cluster.revoke(nodeId);
            cluster.save();
        }
        if (!base.json()) {
            base.out().println("Revoked " + nodeId + (result.path("stopped").asBoolean()
                    ? "; it was in the cluster and has been forced out." : "."));
            base.out().println("To let it back in: c93 cluster invite " + nodeId + " --ca "
                    + (inventory == null ? "./cluster-tls/ca.key" : inventory.getParent().resolve("ca.key")));
        } else {
            Output.json(base.out(), result);
        }
        return 0;
    }
}

/** The shared `cluster revoke` / `node revoke` command fields. */
abstract class RevokeSupport extends Base {

    @Parameters(paramLabel = "ID")
    String nodeId;

    @Option(names = "--file", paramLabel = "FILE", description = "The cluster inventory to update; defaults to the active profile's.")
    Path file;
}
