package automately.runtime.graaljs;

import automately.core.packages.Imports;
import automately.runtime.js.ModuleSources;
import org.graalvm.polyglot.io.FileSystem;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * GraalJS's module loader for one run (D-126): a file system with nothing on it but the run's modules, at virtual paths.
 *
 * <p>GraalJS asks a file system for an import by its specifier alone, never saying who imports it; but which module a
 * name means depends on the importer (a package may import only its own dependencies, D-089). So each module's source is
 * served with its static imports already resolved by {@link ModuleSources} against that module, each specifier rewritten
 * to the resolved module's path. An import the rules refuse becomes a module that throws the reason.
 */
final class GraalJsModules implements FileSystem {

    private static final String JOB_PATH = "/job.mjs";
    private static final String FILE_URL = "file:///";
    private static final String PACKAGES = "/packages/";
    private static final String REFUSED = "/@refused/";

    private final ModuleSources sources;
    /** The engine's rewritten sources of the built-ins every job shares, by URL. */
    private final Map<String, String> sharedSources;
    private final Map<String, ModuleSources.Target> targets = new HashMap<>();
    private final Map<String, String> refused = new HashMap<>();
    private String jobSource;

    GraalJsModules(ModuleSources sources, Map<String, String> sharedSources) {
        this.sources = sources;
        this.sharedSources = sharedSources;
    }

    /** The job's own module, already rewritten for the engine; returns its path. */
    String job(String source) {
        jobSource = rewrite(source, null);
        return pathFor(ModuleSources.JOB_URL);
    }

    /** A built-in's path, resolving it as the job's script would import it. */
    String builtIn(String name) {
        ModuleSources.Target target = sources.target(name, null);
        targets.put(target.url(), target);
        return pathFor(target.url());
    }

    /**
     * A module's path mirrors its URL, so stack traces name it: {@code /job.mjs}, {@code /packages/<name>/<version>/<file>},
     * {@code /node:fs}. It's the same in every context, so a rewritten built-in can be shared.
     */
    static String pathFor(String url) {
        if (url.equals(ModuleSources.JOB_URL)) {
            return JOB_PATH;
        }
        if (url.startsWith(FILE_URL)) {
            return url.substring(FILE_URL.length() - 1);
        }
        return "/" + url;
    }

    private static String urlOf(String path) {
        if (path.equals(JOB_PATH)) {
            return ModuleSources.JOB_URL;
        }
        if (path.startsWith(PACKAGES)) {
            return FILE_URL.substring(0, FILE_URL.length() - 1) + path;
        }
        if (path.startsWith("/@") || path.length() < 2) {
            return null;
        }
        return path.substring(1);
    }

    /** {@code source}'s static imports, each resolved against the module at {@code url} (null for the job's script). */
    private String rewrite(String source, String url) {
        return Imports.rewriteStaticSpecifiers(source, specifier -> {
            try {
                ModuleSources.Target target = sources.target(specifier, url);
                targets.put(target.url(), target);
                return pathFor(target.url());
            } catch (ModuleSources.ImportRefused e) {
                String path = REFUSED + refused.size() + ".mjs";
                refused.put(path, e.getMessage());
                return path;
            }
        });
    }

    private String source(String path) throws IOException {
        String message = refused.get(path);
        if (message != null) {
            // The read fails with the reason, which GraalJS reports as the import's own error, as V8's resolver does.
            throw new IOException(message);
        }
        String url = urlOf(path);
        if (url == null) {
            return null;
        }
        if (url.equals(ModuleSources.JOB_URL)) {
            return jobSource;
        }
        ModuleSources.Target target = targets.get(url);
        if (target == null) {
            // A built-in named in a shared source: its URL is also the name it's imported by.
            try {
                target = sources.target(url, null);
            } catch (ModuleSources.ImportRefused e) {
                return null;
            }
        }
        if (target.cacheable()) {
            // A built-in's imports are other built-ins, resolved the same way in every job.
            String cached = sharedSources.get(url);
            if (cached != null) {
                return cached;
            }
            int refusedBefore = refused.size();
            String rewritten = rewrite(target.source(), url);
            if (refused.size() == refusedBefore) {
                sharedSources.putIfAbsent(url, rewritten);
            }
            return rewritten;
        }
        return rewrite(target.source(), url);
    }

    // --- the file system GraalJS sees ---

    @Override
    public Path parsePath(URI uri) {
        // A specifier that wasn't rewritten (a computed import()): no such module.
        return Paths.get("/@unknown/" + uri.toString().replace('/', '_'));
    }

    @Override
    public Path parsePath(String path) {
        return Paths.get(path.startsWith("/") ? path : "/@unknown/" + path.replace('/', '_'));
    }

    @Override
    public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... linkOptions) throws IOException {
        String name = path.toString();
        if (urlOf(name) == null && !refused.containsKey(name)) {
            throw new NoSuchFileException(name);
        }
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        String source = source(path.toString());
        if (source == null) {
            throw new NoSuchFileException(path.toString());
        }
        return new Bytes(source.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        String name = path.toString();
        if (urlOf(name) == null && !refused.containsKey(name)) {
            throw new NoSuchFileException(name);
        }
        return Map.of("isRegularFile", true, "isDirectory", false, "isSymbolicLink", false, "isOther", false, "size", 0L);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        throw new IOException("Read-only");
    }

    @Override
    public void delete(Path path) throws IOException {
        throw new IOException("Read-only");
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        throw new NoSuchFileException(dir.toString());
    }

    @Override
    public Path toAbsolutePath(Path path) {
        return path.isAbsolute() ? path : Paths.get("/").resolve(path);
    }

    @Override
    public Path toRealPath(Path path, LinkOption... linkOptions) {
        return toAbsolutePath(path);
    }

    /** A module's source, read once. */
    private static final class Bytes implements SeekableByteChannel {
        private final byte[] bytes;
        private int position;
        private boolean open = true;

        Bytes(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read(ByteBuffer dst) {
            if (position >= bytes.length) {
                return -1;
            }
            int n = Math.min(dst.remaining(), bytes.length - position);
            dst.put(bytes, position, n);
            position += n;
            return n;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            throw new IOException("Read-only");
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            position = (int) newPosition;
            return this;
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            throw new IOException("Read-only");
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
