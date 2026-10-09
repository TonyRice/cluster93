package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;

@Command(name = "domains", description = "Serve a service on your own domain (verified by a DNS TXT record).",
        subcommands = {DomainsCommand.Add.class, DomainsCommand.Verify.class, DomainsCommand.Ls.class, DomainsCommand.Rm.class})
final class DomainsCommand extends Base {

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    @Command(name = "add", description = "Claim a domain; prints the TXT record to publish.")
    static final class Add extends Base {
        @Parameters(index = "0", paramLabel = "SERVICE")
        String service;

        @Parameters(index = "1", paramLabel = "DOMAIN")
        String domain;

        @picocli.CommandLine.Option(names = "--port", paramLabel = "PORT", description = "The server port it reaches (a port the service listens on); "
                + "without it, the service's main server.")
        Integer port;

        @Override
        int execute() {
            com.fasterxml.jackson.databind.node.ObjectNode body = Json.object().put("domain", domain);
            if (port != null) {
                body.put("port", port);
            }
            JsonNode claim = api().post("/v1/services/" + Api.segment(service) + "/domains", body);
            if (printedJson(claim)) {
                return 0;
            }
            if (claim.path("verified").asBoolean()) {
                out().println(domain + " is verified and routes to " + service + ".");
            } else {
                JsonNode txt = claim.path("verification");
                out().println("Publish this DNS record, then run: automately domains verify " + service + " " + domain);
                out().println("  " + Json.text(txt, "type") + "  " + Json.text(txt, "name") + "  \"" + Json.text(txt, "value") + "\"");
            }
            return 0;
        }
    }

    @Command(name = "verify", description = "Check the TXT record; the domain routes once verified.")
    static final class Verify extends Base {
        @Parameters(index = "0", paramLabel = "SERVICE")
        String service;

        @Parameters(index = "1", paramLabel = "DOMAIN")
        String domain;

        @Override
        int execute() {
            JsonNode result = api().post("/v1/services/" + Api.segment(service) + "/domains/" + Api.segment(domain) + "/verify", null);
            if (!printedJson(result)) {
                out().println(domain + " is verified and routes to " + service + ".");
            }
            return 0;
        }
    }

    @Command(name = "ls", description = "A service's domains.")
    static final class Ls extends Base {
        @Parameters(paramLabel = "SERVICE")
        String service;

        @Override
        int execute() {
            JsonNode domains = api().get("/v1/services/" + Api.segment(service) + "/domains");
            if (printedJson(domains)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            domains.path("domains").forEach(d -> rows.add(List.of(Json.text(d, "domain"), d.path("verified").asBoolean() ? "yes" : "no",
                    Output.time(d, "createdAt"))));
            Output.table(out(), List.of("DOMAIN", "VERIFIED", "CLAIMED"), rows);
            return 0;
        }
    }

    @Command(name = "rm", description = "Release a domain.")
    static final class Rm extends Base {
        @Parameters(index = "0", paramLabel = "SERVICE")
        String service;

        @Parameters(index = "1", paramLabel = "DOMAIN")
        String domain;

        @Override
        int execute() {
            api().delete("/v1/services/" + Api.segment(service) + "/domains/" + Api.segment(domain));
            out().println("Released " + domain + ".");
            return 0;
        }
    }
}
