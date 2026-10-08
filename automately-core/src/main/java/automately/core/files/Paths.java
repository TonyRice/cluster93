package automately.core.files;

/** Directory arithmetic on file paths, which are relative and {@code /}-separated ({@link FileStore#invalidPath}). */
public final class Paths {

    private Paths() {
    }

    /** The directory holding {@code path}; {@code ""} (the root) for a top-level one. */
    public static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** The last segment of {@code path}. */
    public static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** A directory path as the API takes it: surrounding slashes trimmed, so {@code /a/b/} is {@code a/b} and {@code /} is the root. */
    public static String directory(String path) {
        String trimmed = path == null ? "" : path;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
