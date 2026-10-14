package cluster93.cli;

/** A failure to report to the user as one line, with an exit code. */
public class CliException extends RuntimeException {

    private final int exitCode;

    public CliException(String message) {
        this(message, 1);
    }

    public CliException(String message, int exitCode) {
        super(message, null, false, false);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
