package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Named profiles: one connection context per cluster, dev stack, or staging environment (D-189).
 *
 * <p>A profile is a directory under {@code <config>/profiles/<name>/}. Its {@code config.json} holds the URL, token
 * and tenant (0600); its {@code profile.json} holds non-secret metadata such as a dev stack's ports or a cluster
 * bundle path. The legacy root {@code config.json} is the {@code default} profile, so an existing login keeps
 * working and the default is never moved out from under it.
 */
final class ProfileStore {

    static final String DEFAULT = "default";
    static final String CURRENT = "current-profile";
    static final String PROFILES = "profiles";
    static final String META = "profile.json";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,62}");

    private ProfileStore() {
    }

    /** The active profile: {@code --profile}, then {@code C93_PROFILE}, then the saved marker, then {@code default}. */
    static String active(Io io, String flag) {
        return valid(first(flag, io.env().get("C93_PROFILE"), readCurrent(io), DEFAULT));
    }

    /** Validates a profile name; {@code default} when blank. */
    static String valid(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT;
        }
        String value = name.trim();
        if (!NAME.matcher(value).matches() || ".".equals(value) || "..".equals(value)) {
            throw new CliException("Not a profile name: " + value + " (use letters, digits, '.', '_' or '-')", 2);
        }
        return value;
    }

    /** The profile's directory, whether or not it exists. */
    static Path dir(Io io, String name) {
        return io.configDir().resolve(PROFILES).resolve(valid(name));
    }

    /**
     * The profile's login file. {@code default} prefers the legacy root {@code config.json}; a non-default profile
     * always lives in its own directory.
     */
    static Path configFile(Io io, String name) {
        String profile = valid(name);
        if (!DEFAULT.equals(profile)) {
            return dir(io, profile).resolve(Config.FILE);
        }
        Path legacy = io.configDir().resolve(Config.FILE);
        Path modern = dir(io, profile).resolve(Config.FILE);
        return Files.exists(legacy) || !Files.exists(modern) ? legacy : modern;
    }

    /** The profile's non-secret metadata, if any. */
    static Path metaFile(Io io, String name) {
        return dir(io, name).resolve(META);
    }

    /** The profile marker file, holding the active profile's name. */
    static Path currentFile(Io io) {
        return io.configDir().resolve(CURRENT);
    }

    static String readCurrent(Io io) {
        Path file = currentFile(io);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            return null;
        }
    }

    /** Makes {@code name} the active profile; it must already exist. */
    static void use(Io io, String name) {
        String profile = valid(name);
        if (!exists(io, profile)) {
            throw new CliException("No profile named " + profile + ". Create it with: c93 profiles create " + profile, 2);
        }
        writeAtomically(currentFile(io), profile + "\n");
    }

    static boolean exists(Io io, String name) {
        String profile = valid(name);
        if (DEFAULT.equals(profile)) {
            return true;
        }
        return Files.isDirectory(dir(io, profile));
    }

    /** Every profile on this machine, sorted, with {@code default} present even before its first login. */
    static List<String> list(Io io) {
        List<String> names = new ArrayList<>();
        names.add(DEFAULT);
        Path profiles = io.configDir().resolve(PROFILES);
        if (Files.isDirectory(profiles)) {
            try (var entries = Files.list(profiles)) {
                entries.filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> NAME.matcher(name).matches())
                        .forEach(names::add);
            } catch (IOException e) {
                throw new CliException("Can't read " + profiles + ": " + e.getMessage());
            }
        }
        return names.stream().distinct().sorted().toList();
    }

    static ObjectNode meta(Io io, String name) {
        Path file = metaFile(io, name);
        if (!Files.isRegularFile(file)) {
            return Json.object();
        }
        try {
            JsonNode json = Json.parse(Files.readString(file));
            return json instanceof ObjectNode object ? object : Json.object();
        } catch (IOException e) {
            throw new CliException("Can't read " + file + ": " + e.getMessage());
        }
    }

    static void writeMeta(Io io, String name, ObjectNode meta) {
        writeAtomically(metaFile(io, name), Json.pretty(meta) + System.lineSeparator());
    }

    /** Creates an empty profile directory if it does not exist. */
    static Path create(Io io, String name) {
        Path dir = dir(io, name);
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new CliException("Can't create " + dir + ": " + e.getMessage());
        }
    }

    /** Removes a non-default, non-active profile and everything in it. */
    static void delete(Io io, String name) {
        String profile = valid(name);
        if (DEFAULT.equals(profile)) {
            throw new CliException("The default profile cannot be removed.", 2);
        }
        if (profile.equals(active(io, null))) {
            throw new CliException("Profile " + profile + " is active; switch away with c93 profiles use "
                    + DEFAULT + " first.", 2);
        }
        Path dir = dir(io, profile);
        if (!Files.exists(dir)) {
            throw new CliException("No profile named " + profile + ".", 2);
        }
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new CliException("Can't remove " + dir + ": " + e.getMessage());
        }
    }

    /** Writes a small file with its parent created and 0600 permissions when the platform supports them. */
    static void writeAtomically(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.deleteIfExists(temporary);
            if (Files.getFileStore(file.getParent()).supportsFileAttributeView("posix")) {
                Files.createFile(temporary, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            }
            Files.writeString(temporary, content);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new CliException("Can't write " + file + ": " + e.getMessage());
        }
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
