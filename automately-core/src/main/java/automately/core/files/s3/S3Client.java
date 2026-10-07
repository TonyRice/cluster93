package automately.core.files.s3;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The few S3 calls Automately needs, signed with SigV4 and sent with Vert.x's HTTP client, so they're asynchronous
 * and backpressured like the rest of the node and bring no second networking stack (D-056).
 */
public final class S3Client {

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final S3Settings settings;
    private final HttpClient http;
    private final boolean ssl;
    private final String connectHost;
    private final int port;
    private final String authority;
    private final Vertx vertx;
    private final automately.core.host.EgressPolicy egress;
    private volatile java.net.InetAddress pinned;
    private volatile long pinnedAt;

    public S3Client(Vertx vertx, S3Settings settings) {
        this(vertx, settings, null);
    }

    /**
     * With {@code egress}, the endpoint is resolved here and refused if the policy forbids its address, and every
     * connection goes to the address that was checked, so DNS can't swap in an internal one later (D-026, D-057).
     * Tenants' own buckets use this; the operator's configured bucket doesn't need it.
     */
    public S3Client(Vertx vertx, S3Settings settings, automately.core.host.EgressPolicy egress) {
        this.vertx = vertx;
        this.egress = egress;
        this.settings = settings;
        this.ssl = "https".equalsIgnoreCase(settings.endpoint().getScheme());
        this.port = settings.endpoint().getPort() > 0 ? settings.endpoint().getPort() : ssl ? 443 : 80;
        String host = settings.endpoint().getHost();
        this.connectHost = settings.pathStyle() ? host : settings.bucket() + "." + host;
        boolean defaultPort = settings.endpoint().getPort() < 0 || (ssl && port == 443) || (!ssl && port == 80);
        this.authority = defaultPort ? connectHost : connectHost + ":" + port;
        this.http = vertx.createHttpClient(new HttpClientOptions().setSsl(ssl).setKeepAlive(true).setConnectTimeout(10_000)
                .setIdleTimeout(120), new PoolOptions().setHttp1MaxSize(64));
    }

    public S3Settings settings() {
        return settings;
    }

    /** A failed S3 call: its HTTP status and S3's error code. */
    public static final class S3Exception extends RuntimeException {
        private final int status;
        private final String code;

        S3Exception(int status, String code, String message) {
            super("S3 " + status + (code == null ? "" : " " + code) + (message == null || message.isBlank() ? "" : ": " + message), null, false, false);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    // --- calls ---

    public Future<Void> putObject(String key, Buffer body, String contentType) {
        return send(HttpMethod.PUT, key, new TreeMap<>(), Map.of("content-type", contentType), body).mapEmpty();
    }

    /** Completes with the upload id. */
    public Future<String> createMultipartUpload(String key, String contentType) {
        TreeMap<String, String> query = new TreeMap<>(Map.of("uploads", ""));
        return send(HttpMethod.POST, key, query, Map.of("content-type", contentType), null).map(r -> text(r.body(), "UploadId"));
    }

    /** Completes with the part's ETag, needed to complete the upload. */
    public Future<String> uploadPart(String key, String uploadId, int partNumber, Buffer body) {
        TreeMap<String, String> query = new TreeMap<>(Map.of("partNumber", String.valueOf(partNumber), "uploadId", uploadId));
        return send(HttpMethod.PUT, key, query, Map.of(), body).map(r -> r.headers().get("ETag"));
    }

    public Future<Void> completeMultipartUpload(String key, String uploadId, List<String> etags) {
        StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
        for (int i = 0; i < etags.size(); i++) {
            xml.append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>").append(escape(etags.get(i))).append("</ETag></Part>");
        }
        xml.append("</CompleteMultipartUpload>");
        TreeMap<String, String> query = new TreeMap<>(Map.of("uploadId", uploadId));
        // S3 can answer 200 with an <Error> body here, so the body is checked too.
        return send(HttpMethod.POST, key, query, Map.of("content-type", "application/xml"), Buffer.buffer(xml.toString()))
                .compose(r -> r.body().toString().contains("<Error>") ? Future.failedFuture(error(200, r.body())) : Future.succeededFuture());
    }

    public Future<Void> abortMultipartUpload(String key, String uploadId) {
        return send(HttpMethod.DELETE, key, new TreeMap<>(Map.of("uploadId", uploadId)), Map.of(), null)
                .recover(err -> err instanceof S3Exception s3 && s3.status() == 404 ? Future.succeededFuture() : Future.failedFuture(err))
                .mapEmpty();
    }

    /** Upload ids of unfinished multipart uploads of exactly this key. */
    public Future<List<String>> unfinishedUploads(String key) {
        TreeMap<String, String> query = new TreeMap<>(Map.of("uploads", "", "prefix", key));
        return send(HttpMethod.GET, null, query, Map.of(), null).map(r -> {
            List<String> ids = new ArrayList<>();
            NodeList uploads = parse(r.body()).getElementsByTagName("Upload");
            for (int i = 0; i < uploads.getLength(); i++) {
                Element upload = (Element) uploads.item(i);
                if (key.equals(child(upload, "Key"))) {
                    ids.add(child(upload, "UploadId"));
                }
            }
            return ids;
        });
    }

    /** Bytes {@code start}..{@code endInclusive} as a paused response to stream; 404 fails with an S3Exception. */
    public Future<HttpClientResponse> getRange(String key, long start, long endInclusive) {
        RequestOptions options = request(HttpMethod.GET, key, new TreeMap<>(), Map.of("range", "bytes=" + start + "-" + endInclusive), null);
        return pin(options).compose(pinnedOptions -> http.request(pinnedOptions)).compose(request -> request.send()).compose(response -> {
            if (response.statusCode() == 200 || response.statusCode() == 206) {
                // Paused here, in the callback that delivers it, so no chunk arrives before the reader's handlers.
                response.pause();
                return Future.succeededFuture(response);
            }
            return response.body().compose(body -> Future.failedFuture(error(response.statusCode(), body)));
        });
    }

    public Future<Void> deleteObject(String key) {
        return send(HttpMethod.DELETE, key, new TreeMap<>(), Map.of(), null)
                .recover(err -> err instanceof S3Exception s3 && s3.status() == 404 ? Future.succeededFuture() : Future.failedFuture(err))
                .mapEmpty();
    }

    /** Keys under a prefix (one page, up to 1000). */
    public Future<List<String>> listKeys(String prefix) {
        TreeMap<String, String> query = new TreeMap<>(Map.of("list-type", "2", "prefix", prefix));
        return send(HttpMethod.GET, null, query, Map.of(), null).map(r -> {
            List<String> keys = new ArrayList<>();
            NodeList contents = parse(r.body()).getElementsByTagName("Contents");
            for (int i = 0; i < contents.getLength(); i++) {
                keys.add(child((Element) contents.item(i), "Key"));
            }
            return keys;
        });
    }

    public Future<Void> headBucket() {
        return send(HttpMethod.HEAD, null, new TreeMap<>(), Map.of(), null).mapEmpty();
    }

    /** For tests and first-time setup against MinIO and the like. */
    public Future<Void> createBucket() {
        return send(HttpMethod.PUT, null, new TreeMap<>(), Map.of(), null).mapEmpty();
    }

    // --- plumbing ---

    /** A response, already read in full (only small XML answers come through here). */
    private record Answer(MultiMap headers, Buffer body) {
    }

    private Future<Answer> send(HttpMethod method, String key, SortedMap<String, String> query, Map<String, String> extra, Buffer body) {
        return pin(request(method, key, query, extra, body)).compose(options -> http.request(options))
                .compose(request -> body == null ? request.send() : request.send(body))
                .compose(response -> response.body().compose(data -> response.statusCode() >= 300
                        ? Future.failedFuture(error(response.statusCode(), data))
                        : Future.succeededFuture(new Answer(response.headers(), data))));
    }

    /** Resolved at most once a minute; the checked address is the one connected to. */
    private Future<RequestOptions> pin(RequestOptions options) {
        if (egress == null) {
            return Future.succeededFuture(options);
        }
        java.net.InetAddress cached = pinned;
        if (cached != null && System.currentTimeMillis() - pinnedAt < 60_000) {
            return Future.succeededFuture(options.setServer(io.vertx.core.net.SocketAddress.inetSocketAddress(port, cached.getHostAddress())));
        }
        return vertx.<java.net.InetAddress>executeBlocking(() -> {
            java.net.InetAddress[] all;
            try {
                all = java.net.InetAddress.getAllByName(connectHost);
            } catch (java.net.UnknownHostException e) {
                throw new S3Exception(0, "dns_failed", "Could not resolve " + connectHost);
            }
            return java.util.Arrays.stream(all).filter(egress::allows).findFirst().orElseThrow(() -> new S3Exception(0, "egress_denied",
                    connectHost + " resolves to a private or reserved address, which this tenant's storage policy doesn't allow"));
        }, false).map(address -> {
            pinned = address;
            pinnedAt = System.currentTimeMillis();
            return options.setServer(io.vertx.core.net.SocketAddress.inetSocketAddress(port, address.getHostAddress()));
        });
    }

    /** A signed request. Bucket operations have a null key. */
    private RequestOptions request(HttpMethod method, String key, SortedMap<String, String> query, Map<String, String> extra, Buffer body) {
        String path = (settings.pathStyle() ? "/" + settings.bucket() : "") + "/" + (key == null ? "" : key);
        String canonicalUri = S3Signer.encode(path, true);
        String canonicalQuery = S3Signer.canonicalQuery(query);
        String amzDate = ZonedDateTime.now(ZoneOffset.UTC).format(AMZ_DATE);
        String payloadHash = body == null ? S3Signer.EMPTY_SHA256 : S3Signer.sha256Hex(body.getBytes());
        SortedMap<String, String> signed = new TreeMap<>();
        signed.put("host", authority);
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", amzDate);
        if (settings.sessionToken() != null) {
            signed.put("x-amz-security-token", settings.sessionToken());
        }
        extra.forEach((name, value) -> signed.put(name.toLowerCase(java.util.Locale.ROOT), value));
        String authorization = S3Signer.authorization(method.name(), canonicalUri, canonicalQuery, signed, payloadHash, amzDate,
                settings.region(), settings.accessKeyId(), settings.secretAccessKey());
        RequestOptions options = new RequestOptions().setMethod(method).setHost(connectHost).setPort(port).setSsl(ssl)
                .setURI(canonicalUri + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery));
        signed.forEach((name, value) -> {
            if (!name.equals("host")) {
                options.putHeader(name, value);
            }
        });
        options.putHeader("Host", authority).putHeader("Authorization", authorization);
        return options;
    }

    private static S3Exception error(int status, Buffer body) {
        if (body == null || body.length() == 0) {
            return new S3Exception(status, null, status == 404 ? "Not found" : status == 403 ? "Access denied" : null);
        }
        try {
            Document xml = parse(body);
            return new S3Exception(status, text(xml, "Code"), text(xml, "Message"));
        } catch (RuntimeException e) {
            return new S3Exception(status, null, body.toString());
        }
    }

    /** Parsed with DTDs and external entities off: an answer can't make us read files or URLs (XXE). */
    private static Document parse(Buffer body) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("S3 sent XML that can't be read: " + e.getMessage(), e);
        }
    }

    private static String text(Buffer body, String tag) {
        return text(parse(body), tag);
    }

    private static String text(Document xml, String tag) {
        NodeList nodes = xml.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private static String child(Element element, String tag) {
        NodeList nodes = element.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
