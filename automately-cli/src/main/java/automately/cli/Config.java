package automately.cli;

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

    public static Config resolve(Io io, String urlFlag, String tokenFlag, String tenantFlag) {
        Config saved = load(io);
        String url = first(urlFlag, io.env().get("AUTOMATELY_URL"), saved == null ? null : saved.url());
        String token = first(tokenFlag, io.env().get("AUTOMATELY_TOKEN"), saved == null ? null : saved.token());
        String tenant = first(tenantFlag, io.env().get("AUTOMATELY_TENANT"), saved == null ? null : saved.tenant());
        return new Config(url == null ? null : stripSlash(url), token, tenant);
    }

    public Config require() {
        if (url == null) {
            throw new CliException("Not logged in. Run: automately login <url>   (or set AUTOMATELY_URL and AUTOMATELY_TOKEN)");
        }
        if (token == null) {
            throw new CliException("No token. Run: automately login " + url + "   (or set AUTOMATELY_TOKEN)");
        }
        return this;
    }

    public static Config load(Io io) {
        Path file = io.configDir().resolve(FILE);
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

    /** Saved readable by the user only: it holds a token. */
    public void save(Io io) {
        Path file = io.configDir().resolve(FILE);
        ObjectNode json = Json.object().put("url", url).put("token", token);
        if (tenant != null) {
            json.put("tenant", tenant);
        }
        try {
            Files.createDirectories(io.configDir());
            Path temporary = io.configDir().resolve(FILE + ".tmp");
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
        try {
            return Files.deleteIfExists(io.configDir().resolve(FILE));
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
