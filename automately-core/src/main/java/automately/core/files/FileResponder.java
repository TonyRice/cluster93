package automately.core.files;

import automately.core.runtime.BodyStream;
import automately.core.runtime.HttpExchange;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves a stored file over HTTP: {@code ETag}/{@code If-None-Match} (304), a single {@code Range} (206, or 416 when
 * unsatisfiable), {@code HEAD}, and an exact {@code Content-Length}, so browsers can resume downloads and seek in video.
 * The bytes go from the store to the socket with backpressure; they never pass through a script.
 */
public final class FileResponder {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FileResponder.class);

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private FileResponder() {
    }

    /**
     * @param requestHeaders lower-case request headers (for {@code range} and {@code if-none-match})
     * @param extraHeaders   headers the script added, such as {@code cache-control}
     */
    public static Future<Void> respond(FileStore files, String tenant, String path, String method, JsonObject requestHeaders,
                                       JsonObject extraHeaders, HttpExchange out) {
        return files.stat(tenant, path).compose(file -> {
            if (file == null) {
                out.fail(404, "No such file: " + path);
                return Future.succeededFuture();
            }
            JsonObject headers = extraHeaders == null ? new JsonObject() : extraHeaders.copy();
            headers.put("etag", file.etag()).put("accept-ranges", "bytes");
            if (!headers.containsKey("content-type")) {
                headers.put("content-type", file.contentType());
            }
            if (file.etag().equals(requestHeaders.getString("if-none-match"))) {
                out.respond(304, headers);
                out.end();
                return Future.succeededFuture();
            }
            long start = 0;
            long end = file.size() - 1;
            int status = 200;
            String range = requestHeaders.getString("range");
            if (range != null && file.size() > 0) {
                Matcher m = RANGE.matcher(range.trim());
                if (m.matches() && !(m.group(1).isEmpty() && m.group(2).isEmpty())) {
                    if (m.group(1).isEmpty()) {
                        start = Math.max(0, file.size() - Long.parseLong(m.group(2)));    // bytes=-N: the last N bytes
                    } else {
                        start = Long.parseLong(m.group(1));
                        end = m.group(2).isEmpty() ? end : Math.min(end, Long.parseLong(m.group(2)));
                    }
                    if (start > end || start >= file.size()) {
                        headers.put("content-range", "bytes */" + file.size());
                        out.respond(416, headers);
                        out.end();
                        return Future.succeededFuture();
                    }
                    status = 206;
                    headers.put("content-range", "bytes " + start + "-" + end + "/" + file.size());
                }
            }
            long length = file.size() == 0 ? 0 : end - start + 1;
            headers.put("content-length", String.valueOf(length));
            out.respond(status, headers);
            if ("HEAD".equals(method) || length == 0) {
                out.end();
                return Future.succeededFuture();
            }
            return pipe(files.open(file, start, end), out);
        });
    }

    /** Copies a stream to the exchange one chunk at a time, waiting for each write to drain. */
    private static Future<Void> pipe(BodyStream source, HttpExchange out) {
        Promise<Void> done = Promise.promise();
        source.handler(chunk -> {
            source.pause();
            out.write(chunk).onComplete(ar -> {
                if (ar.succeeded()) {
                    source.resume();
                } else {
                    source.cancel();
                    done.tryFail(ar.cause());
                }
            });
        }, () -> {
            out.end();
            done.tryComplete();
        }, err -> {
            // Once the headers are out, failing means cutting the connection; say why, or nobody can tell.
            LOG.warn("Serving a file failed partway: {}", err.toString(), err.getCause() == null ? null : err.getCause());
            out.fail(500, String.valueOf(err.getMessage()));
            done.tryFail(err);
        });
        source.resume();
        return done.future();
    }
}
