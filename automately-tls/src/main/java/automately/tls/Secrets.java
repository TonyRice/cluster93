package automately.tls;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Writing key material: private keys readable by the owner only (0600 where POSIX permissions exist). */
final class Secrets {

    private Secrets() {
    }

    static void writePrivate(Path file, String text) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.deleteIfExists(file);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(file);
            }
            Files.writeString(file, text, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't write " + file, e);
        }
    }

    static void writePublic(Path file, String text) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't write " + file, e);
        }
    }
}
