package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.io.PrintStream;
import java.util.concurrent.Callable;

/** What every command shares: the root's options, the API, and output. */
@Command
abstract class Base implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @picocli.CommandLine.Option(names = {"-h", "--help"}, usageHelp = true,
            description = "Show this help message and exit.")
    boolean helpRequested;

    Main root() {
        return (Main) spec.root().userObject();
    }

    /** The active connection profile (D-189). */
    String profile() {
        return root().profileName();
    }

    Api api() {
        return root().api();
    }

    Io io() {
        return root().io;
    }

    PrintStream out() {
        return io().out();
    }

    PrintStream err() {
        return io().err();
    }

    boolean json() {
        return root().json;
    }

    /** With --json, prints the node and returns true; the caller prints its summary otherwise. */
    boolean printedJson(JsonNode node) {
        if (json()) {
            Output.json(out(), node);
            return true;
        }
        return false;
    }

    @Override
    public final Integer call() throws Exception {
        return execute();
    }

    abstract int execute() throws Exception;
}
