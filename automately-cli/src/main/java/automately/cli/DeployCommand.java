package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code automately deploy}: one command from a project folder to a running service (D-083 §8, D-091). It publishes the
 * project as a package version (or reuses that version if it's already published with the same files) and points the
 * service at it; the version is the service's revision.
 */
@Command(name = "deploy", description = {
        "Publish the project in DIR and run it as a service: the version in automately.json becomes the service's revision.",
        "The service is named by \"service\": {\"name\"} in automately.json, else the package's name; its \"config\", \"restart\" and \"env\" come from there too."})
final class DeployCommand extends Base {

    @Parameters(paramLabel = "DIR", arity = "0..1", defaultValue = ".", description = "The project folder (default: the current one).")
    Path dir;

    @Option(names = "--name", description = "The service's name (overrides automately.json).")
    String name;

    @Option(names = "--no-bundle", description = "Publish the folder's files as they are (already ES modules), without esbuild.")
    boolean noBundle;

    @Option(names = "--disabled", description = "Define the service without starting it.")
    boolean disabled;

    @Override
    int execute() throws Exception {
        Path root = dir.toAbsolutePath().normalize();
        JsonNode settings = Files.exists(root.resolve("automately.json")) ? Json.parse(Files.readString(root.resolve("automately.json"))) : Json.object();
        String runtime = settings.path("runtime").asText("isolate");
        if (runtime.equals("node")) {
            throw new CliException("\"runtime\": \"node\" runs the project as a Node service, and the Node runtime isn't built yet (D-084); "
                    + "until it is, deploy code that runs in the isolate");
        }
        if (!runtime.equals("isolate")) {
            throw new CliException("\"runtime\" is \"isolate\" (the default) or \"node\", not \"" + runtime + "\"");
        }

        PackagesCommand.Outcome outcome = PackagesCommand.publish(this, root, noBundle, false, true);
        JsonNode service = settings.path("service");
        String serviceName = name != null ? name : service.path("name").asText(serviceName(outcome.project().name()));

        ObjectNode body = Json.object().put("enabled", !disabled);
        body.set("package", Json.object().put("name", outcome.project().name()).put("version", outcome.project().version()));
        if (service.has("config")) {
            body.set("config", service.get("config"));
        }
        if (service.has("restart")) {
            body.set("restart", service.get("restart"));
        }
        if (service.has("env")) {
            body.set("env", service.get("env"));
        }
        if (service.has("ports")) {
            body.set("ports", service.get("ports"));
        }
        JsonNode deployed = api().put("/v1/services/" + Api.segment(serviceName), body);
        if (!printedJson(deployed)) {
            out().println((outcome.unchanged() ? "Using " + outcome.label() + " (already published)"
                    : "Published " + outcome.label() + " (" + outcome.uploadedFiles() + " files uploaded, " + PackagesCommand.kb(outcome.uploadedBytes()) + ")"));
            out().println("Deployed " + outcome.label() + " as service " + serviceName + ", revision " + deployed.path("revision").asInt()
                    + (disabled ? " (not started)" : ""));
            deployed.path("routes").forEach(route -> out().println("  " + ServicesCommand.describeRoute(route)));
        }
        return 0;
    }

    /** A service name from a package name: services allow lowercase letters, digits, '_' and '-'. */
    static String serviceName(String packageName) {
        String local = packageName.startsWith("@cluster/") ? packageName.substring("@cluster/".length()) : packageName;
        return local.replace('.', '-');
    }
}
