package cluster93.cli;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/** Everything the CLI touches outside itself, so tests can run commands in-process. */
public record Io(Map<String, String> env, Path configDir, InputStream in, PrintStream out, PrintStream err, boolean interactive) {

    public static Io system() {
        Map<String, String> env = System.getenv();
        return new Io(env, defaultConfigDir(env), System.in, System.out, System.err, System.console() != null);
    }

    /** {@code C93_CONFIG_DIR}, else the platform's place for per-user configuration. */
    static Path defaultConfigDir(Map<String, String> env) {
        if (env.get("C93_CONFIG_DIR") != null) {
            return Path.of(env.get("C93_CONFIG_DIR"));
        }
        if (System.getProperty("os.name", "").toLowerCase().startsWith("windows") && env.get("APPDATA") != null) {
            return Path.of(env.get("APPDATA"), "cluster93");
        }
        if (env.get("XDG_CONFIG_HOME") != null) {
            return Path.of(env.get("XDG_CONFIG_HOME"), "cluster93");
        }
        return Path.of(System.getProperty("user.home"), ".config", "cluster93");
    }
}
