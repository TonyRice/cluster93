package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;

@Command(name = "tenants", description = "Manage tenants and their tokens (admin token only).",
        subcommands = {TenantsCommand.Create.class, TenantsCommand.Ls.class, TenantsCommand.Rm.class, TenantsCommand.Token.class,
                TenantsCommand.Tokens.class, TenantsCommand.Revoke.class, TenantsCommand.StoragePolicy.class, TenantsCommand.Egress.class})
final class TenantsCommand extends Base {

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    @Command(name = "create", description = "Create a tenant.")
    static final class Create extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            JsonNode tenant = api().post("/v1/tenants", Json.object().put("name", name));
            if (!printedJson(tenant)) {
                out().println("Created tenant " + name + ". Give it a token: c93 tenants token " + name + " <token-name> --scope '*'");
            }
            return 0;
        }
    }

    @Command(name = "ls", description = "Every tenant.")
    static final class Ls extends Base {
        @Override
        int execute() {
            JsonNode tenants = api().get("/v1/tenants");
            if (printedJson(tenants)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            tenants.path("tenants").forEach(t -> rows.add(List.of(Json.text(t, "name"), Output.time(t, "createdAt"))));
            Output.table(out(), List.of("NAME", "CREATED"), rows);
            return 0;
        }
    }

    @Command(name = "rm", description = "Delete an empty tenant and its tokens.")
    static final class Rm extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            api().delete("/v1/tenants/" + Api.segment(name));
            out().println("Deleted tenant " + name + ".");
            return 0;
        }
    }

    @Command(name = "token", description = "Mint a token for a tenant. Prints the secret once.")
    static final class Token extends Base {
        @Parameters(index = "0", paramLabel = "TENANT")
        String tenant;

        @Parameters(index = "1", paramLabel = "NAME")
        String name;

        @Option(names = {"-s", "--scope"}, required = true, paramLabel = "SCOPE", description = "Repeatable; * for every scope.")
        List<String> scopes;

        @Option(names = "--expires-in-days", paramLabel = "DAYS")
        Integer days;

        @Override
        int execute() {
            TokensCommand.printCreated(this, api().post("/v1/tenants/" + Api.segment(tenant) + "/tokens", TokensCommand.request(name, scopes, days)));
            return 0;
        }
    }

    @Command(name = "tokens", description = "A tenant's tokens.")
    static final class Tokens extends Base {
        @Parameters(paramLabel = "TENANT")
        String tenant;

        @Override
        int execute() {
            TokensCommand.list(this, api().get("/v1/tenants/" + Api.segment(tenant) + "/tokens"));
            return 0;
        }
    }

    @Command(name = "storage-policy", description = "Show a tenant's storage policy, or set it with any option.")
    static final class StoragePolicy extends Base {
        @Parameters(paramLabel = "TENANT")
        String tenant;

        @Option(names = "--allow", paramLabel = "TYPE", description = "Repeatable: s3, disk. Replaces what's allowed.")
        List<String> allow;

        @Option(names = "--none", description = "Allow no provider types.")
        boolean none;

        @Option(names = "--disk-mb", paramLabel = "MB", description = "Disk allotment across the tenant's disk providers.")
        Long diskMb;

        @Option(names = "--allow-private-endpoints", negatable = true, description = "Let its S3 endpoints be private addresses.")
        Boolean allowPrivate;

        @Override
        int execute() {
            String path = "/v1/tenants/" + Api.segment(tenant) + "/storage-policy";
            JsonNode current = api().get(path);
            if (allow == null && !none && diskMb == null && allowPrivate == null) {
                Output.json(out(), current);
                return 0;
            }
            com.fasterxml.jackson.databind.node.ArrayNode types = Json.array();
            if (!none) {
                (allow != null ? allow : java.util.stream.StreamSupport.stream(current.path("allow").spliterator(), false).map(JsonNode::asText).toList())
                        .forEach(types::add);
            }
            com.fasterxml.jackson.databind.node.ObjectNode body = Json.object()
                    .put("diskAllotmentMb", diskMb != null ? diskMb : current.path("diskAllotmentMb").asLong())
                    .put("allowPrivateEndpoints", allowPrivate != null ? allowPrivate : current.path("allowPrivateEndpoints").asBoolean());
            body.set("allow", types);
            Output.json(out(), api().put(path, body));
            return 0;
        }
    }

    @Command(name = "egress", description = "Show where a tenant's scripts may connect, or replace its rules with --allow, --deny or --none, "
            + "or set its --posture.")
    static final class Egress extends Base {
        @Parameters(paramLabel = "TENANT")
        String tenant;

        @Option(names = "--allow", paramLabel = "TO:PORTS", description = "Repeatable: a CIDR, host name, *.suffix or @set, then ports, "
                + "such as 10.0.5.20/32:5432, db.internal.example:5432,6379 or 10.1.0.0/16:8000-8099. Replaces the rules.")
        List<String> allow;

        @Option(names = "--deny", paramLabel = "TO[:PORTS]", description = "Repeatable: what to refuse this tenant, such as 203.0.113.0/24 "
                + "or *.example.com:25. Without ports, every port. Replaces the rules.")
        List<String> deny;

        @Option(names = "--posture", paramLabel = "POSTURE", description = "public, allow-list (only what rules allow), or inherit (the cluster's).")
        String posture;

        @Option(names = "--none", description = "Remove every rule and the tenant's own posture: the cluster's policy alone again.")
        boolean none;

        @Override
        int execute() {
            String path = "/v1/tenants/" + Api.segment(tenant) + "/egress-policy";
            JsonNode policy;
            if (allow == null && deny == null && posture == null && !none) {
                policy = api().get(path);
            } else {
                com.fasterxml.jackson.databind.node.ArrayNode rules = Json.array();
                if (allow == null && deny == null && !none) {
                    // Only the posture changes: keep the rules.
                    api().get(path).path("rules").forEach(rules::add);
                }
                for (String rule : none || allow == null ? List.<String>of() : allow) {
                    com.fasterxml.jackson.databind.node.ObjectNode entry = rule("allow", rule, true);
                    if (entry == null) {
                        return 2;
                    }
                    rules.add(entry);
                }
                for (String rule : none || deny == null ? List.<String>of() : deny) {
                    com.fasterxml.jackson.databind.node.ObjectNode entry = rule("deny", rule, false);
                    if (entry == null) {
                        return 2;
                    }
                    rules.add(entry);
                }
                com.fasterxml.jackson.databind.node.ObjectNode body = Json.object();
                body.set("rules", rules);
                if (posture != null && !none) {
                    body.put("posture", posture);
                }
                policy = api().put(path, body);
            }
            if (!printedJson(policy)) {
                EgressCommand.print(this, policy);
            }
            return 0;
        }

        /** {@code TO:PORTS} (or {@code TO} alone for a deny) as a rule; null, with the reason printed, if it isn't one. */
        private com.fasterxml.jackson.databind.node.ObjectNode rule(String action, String text, boolean needsPorts) {
            // An IPv6 CIDR has colons of its own, so the ports are what follows the last colon after any "/".
            int slash = text.indexOf('/');
            int colon = text.lastIndexOf(':');
            boolean hasPorts = colon > 0 && colon > slash && colon < text.length() - 1
                    && text.substring(colon + 1).matches("[0-9@a-z,\\- ]+") && (slash >= 0 || !text.substring(0, colon).contains(":"));
            if (needsPorts && !hasPorts) {
                err().println("--" + action + " takes TO:PORTS, such as 10.0.5.20/32:5432; got \"" + text + "\"");
                return null;
            }
            com.fasterxml.jackson.databind.node.ObjectNode entry = Json.object().put("action", action).put("to", hasPorts ? text.substring(0, colon) : text);
            if (hasPorts) {
                com.fasterxml.jackson.databind.node.ArrayNode ports = Json.array();
                for (String port : text.substring(colon + 1).split(",")) {
                    if (port.trim().matches("\\d{1,5}")) {
                        ports.add(Integer.parseInt(port.trim()));
                    } else {
                        ports.add(port.trim());
                    }
                }
                entry.set("ports", ports);
            }
            return entry;
        }
    }

    @Command(name = "revoke", description = "Revoke one of a tenant's tokens.")
    static final class Revoke extends Base {
        @Parameters(index = "0", paramLabel = "TENANT")
        String tenant;

        @Parameters(index = "1", paramLabel = "ID")
        String id;

        @Override
        int execute() {
            api().delete("/v1/tenants/" + Api.segment(tenant) + "/tokens/" + Api.segment(id));
            out().println("Revoked " + id + ".");
            return 0;
        }
    }
}
