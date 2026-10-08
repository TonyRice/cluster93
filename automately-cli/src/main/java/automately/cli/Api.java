package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Predicate;

/** The REST API (its contract is the server's openapi.yaml), over the JDK's HTTP client. */
public final class Api {

    private final String url;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public Api(Config config) {
        this.url = config.url();
        this.token = config.token();
    }

    public String url() {
        return url;
    }

    public JsonNode get(String path) {
        return json(send("GET", path, HttpRequest.BodyPublishers.noBody(), null));
    }

    public JsonNode post(String path, JsonNode body) {
        return json(send("POST", path, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.write(body)),
                "application/json"));
    }

    public JsonNode put(String path, JsonNode body) {
        return json(send("PUT", path, HttpRequest.BodyPublishers.ofString(Json.write(body)), "application/json"));
    }

    public JsonNode patch(String path, JsonNode body) {
        return json(send("PATCH", path, HttpRequest.BodyPublishers.ofString(Json.write(body)), "application/json"));
    }

    public JsonNode delete(String path) {
        return json(send("DELETE", path, HttpRequest.BodyPublishers.noBody(), null));
    }

    /** Streams a local file up, as it's read. */
    public JsonNode upload(String path, Path file, String contentType) {
        HttpRequest.BodyPublisher body;
        try {
            body = HttpRequest.BodyPublishers.ofFile(file);
        } catch (java.io.FileNotFoundException e) {
            throw new CliException("No such file: " + file);
        }
        return json(send("PUT", path, body, contentType));
    }

    /** Sends bytes already in memory. */
    public JsonNode putBytes(String path, byte[] bytes, String contentType) {
        return json(send("PUT", path, HttpRequest.BodyPublishers.ofByteArray(bytes), contentType));
    }

    /** A response body as a stream; the caller closes it. Fails on an error status. */
    public InputStream download(String path) {
        HttpResponse<InputStream> response = request("GET", path, HttpRequest.BodyPublishers.noBody(), null);
        if (response.statusCode() >= 400) {
            throw error(response.statusCode(), readAll(response.body()));
        }
        return response.body();
    }

    /**
     * Reads a job's server-sent events, calling {@code onEvent} with each one's JSON until it returns false or the
     * stream ends. Completes with the last event.
     */
    public JsonNode events(String jobId, Predicate<JsonNode> onEvent) {
        HttpResponse<InputStream> response = request("GET", "/v1/jobs/" + segment(jobId) + "/events", HttpRequest.BodyPublishers.noBody(), null);
        if (response.statusCode() >= 400) {
            throw error(response.statusCode(), readAll(response.body()));
        }
        JsonNode last = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                } else if (line.isEmpty() && !data.isEmpty()) {
                    last = Json.parse(data.toString());
                    data.setLength(0);
                    if (!onEvent.test(last)) {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            throw new CliException("The event stream broke: " + e.getMessage());
        }
        return last;
    }

    /** One path segment, percent-encoded. */
    public static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** A file path: each segment encoded, the slashes kept. */
    public static String filePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String part : path.split("/", -1)) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(segment(part));
        }
        return out.toString();
    }

    private String send(String method, String path, HttpRequest.BodyPublisher body, String contentType) {
        HttpResponse<InputStream> response = request(method, path, body, contentType);
        String text = readAll(response.body());
        if (response.statusCode() >= 400) {
            throw error(response.statusCode(), text);
        }
        return text;
    }

    private HttpResponse<InputStream> request(String method, String path, HttpRequest.BodyPublisher body, String contentType) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url + path)).method(method, body)
                .header("Accept", "application/json").header("User-Agent", "automately-cli/" + Main.version());
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new CliException("Can't reach " + url + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("Interrupted");
        }
    }

    private static JsonNode json(String text) {
        return text.isBlank() ? null : Json.parse(text);
    }

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CliException("The response broke off: " + e.getMessage());
        }
    }

    private static ApiException error(int status, String body) {
        try {
            JsonNode error = Json.parse(body).get("error");
            if (error != null) {
                return new ApiException(status, Json.text(error, "code"), Json.text(error, "message"));
            }
        } catch (CliException ignored) {
            // not the envelope
        }
        return new ApiException(status, String.valueOf(status), "HTTP " + status + (body.isBlank() ? "" : ": " + body.strip()));
    }
}
