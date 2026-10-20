package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Where to connect, with which token, and in which tenant's context: flags, then environment, then the saved login. */
public record Config(String url, String token, String tenant) {

    static final String FILE = "config.json";

    public Config(String url, String token) {
        this(url, token, null);
    }

    /** The active profile's configuration: flags, then environment, then the saved login. */
    public static Config resolve(Io io, String urlFlag, String tokenFlag, String tenantFlag) {
        return resolve(io, ProfileStore.active(io, null), urlFlag, tokenFlag, tenantFlag);
    }

    /** One profile's configuration: flags, then environment, then that profile's saved login. */
    public static Config resolve(Io io, String profile, String urlFlag, String tokenFlag, String tenantFlag) {
        String selected = profile == null || profile.isBlank() ? ProfileStore.active(io, null) : ProfileStore.valid(profile);
        Config saved = load(io, selected);
        String url = first(urlFlag, io.env().get("C93_URL"), saved == null ? null : saved.url());
        String token = first(tokenFlag, io.env().get("C93_TOKEN"), saved == null ? null : saved.token());
        String tenant = first(tenantFlag, io.env().get("C93_TENANT"), saved == null ? null : saved.tenant());
        return new Config(url == null ? null : stripSlash(url), token, tenant);
    }

    public Config require() {
        if (url == null) {
            throw new CliException("Not logged in. Run: c93 login <url>   (or set C93_URL and C93_TOKEN)");
        }
        if (token == null) {
            throw new CliException("No token. Run: c93 login " + url + "   (or set C93_TOKEN)");
        }
        return this;
    }

    public static Config load(Io io) {
        return load(io, ProfileStore.active(io, null));
    }

    /** One profile's saved login, or null when it has none. */
    public static Config load(Io io, String profile) {
        Path file = ProfileStore.configFile(io, profile);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            JsonNode json = Json.parse(Files.readString(file));
            return new Config(Json.text(json, "url").isEmpty() ? null : Json.text(json, "url"),
                    Json.text(json, "token").isEmpty() ? null : Json.text(json, "token"),
                    Json.text(json, "tenant").isEmpty() ? null : Json.text(json, "tenant"));
        } catch (IOException e) {
            throw new CliException("Can't read " + file + ": " + e.getMessage());
        }
    }

    /** The file this profile's login lives in; the default profile keeps the legacy root path when it exists. */
    public static Path file(Io io, String profile) {
        return ProfileStore.configFile(io, profile);
    }

    /** Saved readable by the user only: it holds a token. */
    public void save(Io io) {
        save(io, ProfileStore.active(io, null));
    }

    /** Saves this login into one profile, creating the directory and the file 0600. */
    public void save(Io io, String profile) {
        Path file = ProfileStore.configFile(io, profile);
        ObjectNode json = Json.object().put("url", url).put("token", token);
        if (tenant != null) {
            json.put("tenant", tenant);
        }
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.deleteIfExists(temporary);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            Files.writeString(temporary, Json.pretty(json));
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new CliException("Can't write " + file + ": " + e.getMessage());
        }
    }

    public static boolean forget(Io io) {
        return forget(io, ProfileStore.active(io, null));
    }

    /** Forgets one profile's login; the default profile clears both the legacy and the modern file if both exist. */
    public static boolean forget(Io io, String profile) {
        try {
            Path file = ProfileStore.configFile(io, profile);
            boolean removed = Files.deleteIfExists(file);
            if (ProfileStore.DEFAULT.equals(ProfileStore.valid(profile))) {
                Path modern = ProfileStore.dir(io, profile).resolve(FILE);
                if (!modern.equals(file)) {
                    removed |= Files.deleteIfExists(modern);
                }
            }
            return removed;
        } catch (IOException e) {
            throw new CliException("Can't remove the saved login: " + e.getMessage());
        }
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
