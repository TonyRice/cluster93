package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Command(name = "services", description = "Deploy and manage long-running services.",
        subcommands = {ServicesCommand.Deploy.class, ServicesCommand.Ls.class, ServicesCommand.Get.class, ServicesCommand.Routes.class, ServicesCommand.Logs.class,
                ServicesCommand.Start.class, ServicesCommand.Stop.class, ServicesCommand.Restart.class, ServicesCommand.Rm.class})
final class ServicesCommand extends Base {

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    static void summarize(Base command, JsonNode service) {
        if (command.printedJson(service)) {
            return;
        }
        List<String> hosts = new ArrayList<>();
        service.path("hosts").forEach(host -> hosts.add(host.asText()));
        command.out().println(Json.text(service, "name") + ": " + Json.text(service, "status") + ", revision " + Json.text(service, "revision")
                + ", " + Json.text(service, "runs") + " run(s)" + (hosts.isEmpty() ? "" : ", serving " + String.join(", ", hosts)));
    }

    @Command(name = "deploy", description = "Create or update a service from a script; the cluster rolls it to the new revision.")
    static final class Deploy extends Base {
        @Parameters(index = "0", paramLabel = "NAME")
        String name;

        @Parameters(index = "1", paramLabel = "SCRIPT")
        Path script;

        @Option(names = "--config", paramLabel = "JSON", description = "The service's config, a JSON object (ctx.service.config).")
        String config;

        @Option(names = "--restart", paramLabel = "POLICY", description = "always (default) or on-failure.")
        String restart;

        @Option(names = "--disabled", description = "Define it without starting it.")
        boolean disabled;

        @Option(names = {"-e", "--env"}, paramLabel = "NAME=VALUE", description = "Set process.env.NAME for its runs; repeat for more.")
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();

        @Option(names = "--env-secret", paramLabel = "NAME=SECRET", description = "Set process.env.NAME to one of your secrets, read when each run starts.")
        java.util.Map<String, String> envSecrets = new java.util.LinkedHashMap<>();

        @Override
        int execute() throws Exception {
            ObjectNode body = Json.object().put("script", Files.readString(script));
            if (!env.isEmpty() || !envSecrets.isEmpty()) {
                ObjectNode declared = Json.object();
                env.forEach(declared::put);
                envSecrets.forEach((key, secret) -> declared.set(key, Json.object().put("secret", secret)));
                body.set("env", declared);
            }
            if (config != null) {
                body.set("config", Json.parse(config));
            }
            if (restart != null) {
                body.put("restart", restart);
            }
            if (disabled) {
                body.put("enabled", false);
            }
            summarize(this, api().put("/v1/services/" + Api.segment(name), body));
            return 0;
        }
    }

    @Command(name = "ls", description = "Your services.")
    static final class Ls extends Base {
        @Override
        int execute() {
            JsonNode services = api().get("/v1/services");
            if (printedJson(services)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            services.path("services").forEach(s -> {
                List<String> hosts = new ArrayList<>();
                s.path("hosts").forEach(h -> hosts.add(h.asText()));
                rows.add(List.of(Json.text(s, "name"), Json.text(s, "status"), Json.text(s, "revision"), Json.text(s, "runs"),
                        String.join(" ", hosts)));
            });
            Output.table(out(), List.of("NAME", "STATUS", "REVISION", "RUNS", "HOSTS"), rows);
            return 0;
        }
    }

    @Command(name = "get", description = "One service, in full.")
    static final class Get extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            Output.json(out(), api().get("/v1/services/" + Api.segment(name)));
            return 0;
        }
    }

    @Command(name = "routes", description = "Where the service's servers are reached: hosts, path prefixes and domains, with their ports.")
    static final class Routes extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            JsonNode service = api().get("/v1/services/" + Api.segment(name));
            if (printedJson(service.path("routes"))) {
                return 0;
            }
            service.path("routes").forEach(route -> out().println(describeRoute(route)));
            out().println("Any other port the service listens on is reached at <service>--<tenant>--<port>, as the ports above.");
            return 0;
        }
    }

    /** One route as people read it: "https://host/path -> port 3001", or the main server. */
    static String describeRoute(JsonNode route) {
        String target = route.has("port") ? "port " + route.path("port").asInt() : "the main server";
        if (!route.path("exposed").asBoolean(true)) {
            return target + ": not exposed";
        }
        return Json.text(route, "host") + (route.has("path") ? Json.text(route, "path") : "") + " -> " + target;
    }

    @Command(name = "logs", description = "Follow the service's current run: its recent lines, then live.")
    static final class Logs extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            Api api = api();
            JsonNode service = api.get("/v1/services/" + Api.segment(name));
            String run = Json.text(service, "currentJobId");
            if (run.isEmpty()) {
                throw new CliException("Service " + name + " has no current run (" + Json.text(service, "status") + ")");
            }
            return JobsCommand.follow(this, api, run, false);
        }
    }

    @Command
    abstract static class Action extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        int act(String action) {
            summarize(this, api().post("/v1/services/" + Api.segment(name) + "/" + action, null));
            return 0;
        }
    }

    @Command(name = "start", description = "Start (enable) a service.")
    static final class Start extends Action {
        @Override
        int execute() {
            return act("start");
        }
    }

    @Command(name = "stop", description = "Stop (disable) a service; it gets a graceful stop.")
    static final class Stop extends Action {
        @Override
        int execute() {
            return act("stop");
        }
    }

    @Command(name = "restart", description = "Restart a service's current run.")
    static final class Restart extends Action {
        @Override
        int execute() {
            return act("restart");
        }
    }

    @Command(name = "rm", description = "Stop and remove a service (releases its domains).")
    static final class Rm extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            api().delete("/v1/services/" + Api.segment(name));
            out().println("Removed " + name + ".");
            return 0;
        }
    }
}
