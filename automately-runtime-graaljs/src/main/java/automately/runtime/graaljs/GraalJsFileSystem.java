package automately.runtime.graaljs;

import org.graalvm.polyglot.io.FileSystem;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.util.Map;
import java.util.Set;

/**
 * A context's file system: empty until the run's modules are known, then {@link GraalJsModules}. A context's file system
 * is fixed when it's built, before the run's packages are, hence the indirection. Nothing else is ever on it (D-126).
 */
final class GraalJsFileSystem implements FileSystem {

    private volatile GraalJsModules modules;

    void serve(GraalJsModules modules) {
        this.modules = modules;
    }

    private GraalJsModules modules(Path path) throws IOException {
        GraalJsModules current = modules;
        if (current == null) {
            throw new NoSuchFileException(String.valueOf(path));
        }
        return current;
    }

    @Override
    public Path parsePath(URI uri) {
        GraalJsModules current = modules;
        return current == null ? Paths.get("/@unknown") : current.parsePath(uri);
    }

    @Override
    public Path parsePath(String path) {
        GraalJsModules current = modules;
        return current == null ? Paths.get("/@unknown") : current.parsePath(path);
    }

    @Override
    public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... linkOptions) throws IOException {
        modules(path).checkAccess(path, modes, linkOptions);
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
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        return modules(path).newByteChannel(path, options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        throw new NoSuchFileException(String.valueOf(dir));
    }

    @Override
    public Path toAbsolutePath(Path path) {
        return path.isAbsolute() ? path : Paths.get("/").resolve(path);
    }

    @Override
    public Path toRealPath(Path path, LinkOption... linkOptions) {
        return toAbsolutePath(path);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return modules(path).readAttributes(path, attributes, options);
    }
}
