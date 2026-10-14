package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Jackson's tree model only: nothing is bound to classes by reflection, which keeps native-image simple. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    /** The strings as a JSON array; empty if null. */
    public static ArrayNode strings(java.util.List<String> values) {
        ArrayNode array = array();
        if (values != null) {
            values.forEach(array::add);
        }
        return array;
    }

    public static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new CliException("Not valid JSON: " + e.getMessage().lines().findFirst().orElse(""));
        }
    }

    public static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pretty(JsonNode node) {
        try {
            return PRETTY.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A field as text, or "" when missing or null. */
    public static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.isTextual() ? value.asText() : write(value);
    }
}
