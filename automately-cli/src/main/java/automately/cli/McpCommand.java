package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The MCP stdio bridge (D-047): an MCP client that only launches local processes runs {@code automately mcp}, which
 * forwards each JSON-RPC message from stdin to the cluster's {@code /mcp} endpoint with the saved token, and writes the
 * answers to stdout. Logs go to stderr; stdout carries only protocol messages.
 */
@Command(name = "mcp", description = "Serve MCP over stdio for AI agents, forwarding to the cluster's /mcp endpoint.")
final class McpCommand extends Base {

    @Override
    int execute() throws IOException {
        Config config = root().config().require();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        String session = null;
        BufferedReader in = new BufferedReader(new InputStreamReader(io().in(), StandardCharsets.UTF_8));
        err().println("automately mcp: bridging stdio to " + config.url() + "/mcp");
        for (String line; (line = in.readLine()) != null; ) {
            if (line.isBlank()) {
                continue;
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.url() + "/mcp"))
                    .header("Authorization", "Bearer " + config.token())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(line));
            if (session != null) {
                request.header("Mcp-Session-Id", session);
            }
            HttpResponse<InputStream> response;
            try {
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            } catch (IOException e) {
                reply(error(line, -32000, "Can't reach " + config.url() + ": " + e.getMessage()));
                continue;
            }
            session = response.headers().firstValue("Mcp-Session-Id").orElse(session);
            String type = response.headers().firstValue("Content-Type").orElse("");
            try (InputStream body = response.body()) {
                if (response.statusCode() == 202) {
                    continue;
                }
                if (type.startsWith("text/event-stream")) {
                    relayEvents(body);
                } else {
                    String text = new String(body.readAllBytes(), StandardCharsets.UTF_8).strip();
                    if (response.statusCode() >= 400 && !text.startsWith("{\"jsonrpc\"")) {
                        reply(error(line, -32000, "HTTP " + response.statusCode() + ": " + text));
                    } else if (!text.isEmpty()) {
                        reply(text);
                    }
                }
            }
        }
        return 0;
    }

    private void relayEvents(InputStream body) throws IOException {
        BufferedReader events = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        for (String line; (line = events.readLine()) != null; ) {
            if (line.startsWith("data:")) {
                data.append(line.substring(5).trim());
            } else if (line.isEmpty() && !data.isEmpty()) {
                reply(data.toString());
                data.setLength(0);
            }
        }
        if (!data.isEmpty()) {
            reply(data.toString());
        }
    }

    private void reply(String message) {
        if (message == null) {
            return;
        }
        // One JSON-RPC message per line, as MCP's stdio transport requires.
        out().println(Json.write(Json.parse(message)));
        out().flush();
    }

    /** A JSON-RPC error for the request on this line, or null if it was a notification (no id). */
    private static String error(String line, int code, String message) {
        JsonNode request;
        try {
            request = Json.parse(line);
        } catch (CliException e) {
            return Json.write(Json.object().put("jsonrpc", "2.0").putNull("id")
                    .set("error", Json.object().put("code", -32700).put("message", "Parse error")));
        }
        if (!request.has("id")) {
            return null;
        }
        return Json.write(Json.object().put("jsonrpc", "2.0").<com.fasterxml.jackson.databind.node.ObjectNode>set("id", request.get("id"))
                .set("error", Json.object().put("code", code).put("message", message)));
    }
}
