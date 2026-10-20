package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The operator's cluster inventory, beside the CA: the nodes that were issued certificates, their addresses, expiry
 * and join/revoke state (D-188). It holds references only — never a private key, and never the CA key's contents.
 *
 * <p>The live membership is still the cluster's: {@code /v1/nodes} is the authority for what is running. This file is
 * what lets {@code cluster status/doctor} talk about configured-but-missing and live-but-unlisted nodes, and what
 * {@code cluster invite/revoke} update.
 */
final class ClusterFile {

    static final String FILE = "cluster.json";

    private final Path file;
    private final ObjectNode json;

    private ClusterFile(Path file, ObjectNode json) {
        this.file = file;
        this.json = json;
    }

    /** A directory means {@code <dir>/cluster.json}; a file is used as given. */
    static Path resolve(Path path) {
        if (path == null) {
            return null;
        }
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.isDirectory(absolute) || !FILE.equals(absolute.getFileName().toString())) {
            return absolute.resolve(FILE);
        }
        return absolute;
    }

    static ClusterFile load(Path path) {
        Path file = resolve(path);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode parsed = Json.parse(Files.readString(file));
            if (!(parsed instanceof ObjectNode object)) {
                throw new CliException("Not a cluster inventory: " + file, 2);
            }
            return new ClusterFile(file, object);
        } catch (IOException e) {
            throw new CliException("Can't read " + file + ": " + e.getMessage());
        }
    }

    static ClusterFile create(Path path, String name, String api) {
        Path file = resolve(path);
        ObjectNode json = Json.object().put("version", 1);
        if (name != null && !name.isBlank()) {
            json.put("name", name);
        }
        if (api != null && !api.isBlank()) {
            json.put("api", api);
        }
        json.set("nodes", Json.array());
        ClusterFile cluster = new ClusterFile(file, json);
        cluster.save();
        return cluster;
    }

    Path file() {
        return file;
    }

    Path dir() {
        return file.getParent();
    }

    ObjectNode json() {
        return json;
    }

    String name() {
        return json.path("name").asText("");
    }

    String api() {
        return json.path("api").asText("");
    }

    ArrayNode nodes() {
        JsonNode nodes = json.get("nodes");
        if (nodes instanceof ArrayNode array) {
            return array;
        }
        ArrayNode created = Json.array();
        json.set("nodes", created);
        return created;
    }

    ObjectNode node(String id) {
        for (JsonNode node : nodes()) {
            if (id.equals(node.path("id").asText())) {
                return (ObjectNode) node;
            }
        }
        return null;
    }

    /** Adds or replaces a node's certificate record; paths are stored relative to the inventory when possible. */
    void putNode(String id, List<String> addresses, Path certificate, Instant expires, Path join) {
        ObjectNode node = node(id);
        if (node == null) {
            node = Json.object().put("id", id);
            nodes().add(node);
        }
        node.set("addresses", Json.strings(addresses));
        if (certificate != null) {
            node.put("certificate", relative(certificate));
        }
        if (expires != null) {
            node.put("expires", expires.toString());
        }
        if (join != null) {
            node.put("join", relative(join));
        }
        node.put("revoked", false);
    }

    void revoke(String id) {
        ObjectNode node = node(id);
        if (node != null) {
            node.put("revoked", true);
        }
    }

    /** Resolves a path stored in the inventory relative to its directory. */
    Path relative(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Path path = Path.of(value);
        return path.isAbsolute() ? path : dir().resolve(path).normalize();
    }

    void save() {
        ProfileStore.writeAtomically(file, Json.pretty(json) + System.lineSeparator());
    }

    private String relative(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.startsWith(dir()) ? dir().relativize(absolute).toString() : absolute.toString();
        } catch (IllegalArgumentException e) {
            return absolute.toString();
        }
    }

    /** The configured node ids in file order. */
    List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : nodes()) {
            String id = node.path("id").asText("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }
}
