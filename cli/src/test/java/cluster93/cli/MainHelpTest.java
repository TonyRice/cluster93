package cluster93.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The top-level usage help: picocli's flat command list is replaced by one categorized section per group, while
 * subcommands keep their own command lists. {@link #CATEGORIES} repeats {@code Main}'s grouping so the two can't
 * drift apart unnoticed.
 */
class MainHelpTest {

    /** Every category heading, and the commands shown under it, in the order the help prints them. */
    private static final Map<String, List<String>> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("Developer & Applications", List.of("apps", "deploy", "run", "jobs", "services"));
        CATEGORIES.put("Platform Resources",
                List.of("files", "secrets", "queues", "packages", "storage", "domains", "egress", "callbacks"));
        CATEGORIES.put("Identity & Access",
                List.of("login", "logout", "whoami", "me", "profiles", "tokens", "members", "roles", "groups", "invites"));
        CATEGORIES.put("Cluster & Node", List.of("cluster", "node", "update"));
        CATEGORIES.put("Tools & Automation", List.of("mcp", "version", "help"));
    }

    @TempDir
    Path home;

    /** Runs the CLI with a temp config dir and no server; returns what it printed. */
    private String run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Io io = new Io(Map.of(), home.resolve("config"), new ByteArrayInputStream(new byte[0]),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), false);
        int code = Main.run(io, args);
        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theHelpShowsEveryCategoryHeadingInOrder() {
        String help = run("--help");

        int previous = -1;
        for (String heading : CATEGORIES.keySet()) {
            int at = help.indexOf(heading + ":");
            assertThat(at).as("the heading '%s' is present and follows the previous one", heading).isGreaterThan(previous);
            previous = at;
        }
    }

    @Test
    void eachCommandIsListedUnderItsOwnCategory() {
        String help = run("--help");
        List<String> headings = List.copyOf(CATEGORIES.keySet());

        for (int i = 0; i < headings.size(); i++) {
            String heading = headings.get(i);
            int start = help.indexOf(heading + ":");
            int end = i + 1 < headings.size() ? help.indexOf(headings.get(i + 1) + ":") : help.length();
            String section = help.substring(start, end);
            for (String command : CATEGORIES.get(heading)) {
                // A command line is "  <name>" padded to the group's name column, then its description.
                assertThat(section)
                        .as("'%s' is listed under %s", command, heading)
                        .containsPattern("(?m)^ {2}" + Pattern.quote(command) + " {2,}\\S");
            }
        }
    }

    @Test
    void theFlatCommandListIsGoneButSubcommandsKeepTheirs() {
        String help = run("--help");
        assertThat(help).as("no single flat 'Commands:' list at the top level").doesNotContain("Commands:");
        assertThat(help).as("the deprecated alias is hidden").doesNotContain("  local ");
        assertThat(help).as("moved admin commands are hidden at the root")
                .doesNotContain("  tenants ").doesNotContain("  users ").doesNotContain("  audit ");

        String node = run("node");
        assertThat(node).contains("Commands:").contains("status").contains("start").contains("setup");
    }

    @Test
    void theHelpFlagWorksOnSubcommands() {
        assertThat(run("node", "--help")).contains("Usage: c93 node");
        assertThat(run("cluster", "--help")).contains("Usage: c93 cluster");
        assertThat(run("profiles", "--help")).contains("Usage: c93 profiles");
        assertThat(run("me", "--help")).contains("Usage: c93 me");
    }

    @Test
    void theClusterGroupListsTheMovedAdminCommands() {
        String cluster = run("cluster", "--help");
        assertThat(cluster).contains("tenants").contains("users").contains("audit")
                .contains("status").contains("doctor").contains("invite").contains("revoke");
    }

    @Test
    void theHelpHasNoInternalDecisionTags() {
        assertThat(run("--help")).as("internal decision tags like '(D-070)' stay out of the help").doesNotContain("(D-");
    }

    @Test
    void everySubcommandBelongsToExactlyOneCategory() {
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (List<String> commands : CATEGORIES.values()) {
            for (String command : commands) {
                assertThat(seen.add(command)).as("'%s' is grouped once", command).isTrue();
                total++;
            }
        }
        assertThat(total).as("all 29 root subcommands are grouped").isEqualTo(29);
    }
}
