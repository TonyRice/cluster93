package cluster93.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Finding and building the checkout a {@code node install --checkout} or {@code c93 dev} runs from. */
final class Checkout {

    private Checkout() {
    }

    /** {@code C93_REPO}, then the working directory and its parents, looking for the repository's poms. */
    static Path root(Io io) {
        String explicit = io.env().get("C93_REPO");
        if (explicit == null) {
            explicit = System.getenv("C93_REPO");
        }
        if (explicit != null && !explicit.isBlank()) {
            Path path = Path.of(explicit).toAbsolutePath().normalize();
            return isCheckout(path) ? path : null;
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (isCheckout(dir)) {
                return dir;
            }
        }
        return null;
    }

    static Path require(Io io) {
        Path root = root(io);
        if (root == null) {
            throw new CliException("No Cluster93 checkout here. Run this from the repository, or set C93_REPO.", 2);
        }
        return root;
    }

    static boolean isCheckout(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"))
                && Files.isDirectory(dir.resolve("server"))
                && Files.isDirectory(dir.resolve("worker"));
    }

    static Path serverJar(Path root) {
        return root.resolve("server").resolve("target").resolve(NodeInstall.SERVER_JAR);
    }

    static boolean hasWorkers(Path root) {
        return Files.isExecutable(root.resolve("bin").resolve(NodeInstall.WORKER_JAVET_BIN))
                && Files.isExecutable(root.resolve("bin").resolve(NodeInstall.WORKER_GRAALJS_BIN));
    }

    /**
     * Builds what the install needs from the checkout. Existing artifacts are reused; Maven and the worker build
     * script run only when a piece is missing. The worker build is long and prints progress.
     */
    static void ensureBuilt(Io io, Path root, boolean quiet) throws IOException, InterruptedException {
        if (!Files.isRegularFile(serverJar(root))) {
            if (!quiet) {
                io.out().println("Building the server from " + root);
            }
            run(io, root, List.of("./mvnw", "-q", "-pl", "server", "-am", "package", "-DskipTests", "-DskipITs"),
                    "build the server");
        }
        if (!hasWorkers(root)) {
            Path script = root.resolve("scripts").resolve("build-workers.sh");
            if (!Files.isExecutable(script)) {
                throw new CliException("No bin/worker-javet and bin/worker-graaljs, and no scripts/build-workers.sh to build them");
            }
            if (!quiet) {
                io.out().println("Building the native workers (this can take several minutes)");
            }
            run(io, root, List.of(script.toString(), "all"), "build the workers");
        }
    }

    private static void run(Io io, Path root, List<String> command, String what) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile()).inheritIO();
        Process process = builder.start();
        if (process.waitFor() != 0) {
            throw new CliException("Couldn't " + what + ": " + String.join(" ", command)
                    + " exited " + process.exitValue());
        }
    }
}
