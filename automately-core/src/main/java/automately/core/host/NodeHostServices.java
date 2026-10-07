package automately.core.host;

import automately.core.files.FileResponder;
import automately.core.files.FileStore;
import automately.core.files.StorageCapacity;
import automately.core.runtime.FetchedResponse;
import automately.core.runtime.HostException;
import automately.core.runtime.HttpExchange;
import automately.core.runtime.HostServices;
import automately.core.runtime.Inbound;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The host APIs for one execution, already scoped to its tenant (docs/design/host-apis.md). Validates
 * arguments and limits, then delegates to the {@link KvStore}, the event bus, the {@link FetchClient} and
 * the node's {@link ServeRegistry}. Everything it registers is undone by {@link #close()}.
 */
public final class NodeHostServices implements HostServices {

    public static final int MAX_NAME_BYTES = 512;
    public static final int MAX_VALUE_BYTES = 256 * 1024;
    public static final int MAX_LIST_LIMIT = 1_000;
    public static final int MAX_CONCURRENT_FETCHES = 16;
    public static final long MAX_REQUEST_TIMEOUT_MS = 30_000;
    static final String TOPIC_PATTERN = "[a-zA-Z0-9._-]{1,128}";

    private final Vertx vertx;
    private final String tenant;
    private final String jobId;
    private final boolean service;
    private final KvStore kv;
    private final FetchClient fetch;
    private final ServeRegistry serve;
    private final FileStore files;
    private final StorageCapacity capacity;
    private final List<automately.core.files.FileWriter> openWriters = new CopyOnWriteArrayList<>();
    private final List<Registration> registrations = new CopyOnWriteArrayList<>();
    private final AtomicInteger fetchesInFlight = new AtomicInteger();

    public NodeHostServices(Vertx vertx, String tenant, String jobId, boolean service, KvStore kv, FetchClient fetch, ServeRegistry serve) {
        this(vertx, tenant, jobId, service, kv, fetch, serve, null, StorageCapacity.UNLIMITED);
    }

    public NodeHostServices(Vertx vertx, String tenant, String jobId, boolean service, KvStore kv, FetchClient fetch, ServeRegistry serve,
                            FileStore files, StorageCapacity capacity) {
        this.files = files;
        this.capacity = capacity;
        this.vertx = vertx;
        this.tenant = tenant;
        this.jobId = jobId;
        this.service = service;
        this.kv = kv;
        this.fetch = fetch;
        this.serve = serve;
    }

    /** A factory sharing one store, client and registry across a node's executions. */
    public static HostServicesFactory factory(Vertx vertx, KvStore kv, FetchClient fetch, ServeRegistry serve, FileStore files,
                                              StorageCapacity capacity) {
        return (tenant, jobId, service) -> new NodeHostServices(vertx, tenant, jobId, service, kv, fetch, serve, files, capacity);
    }

    @Override
    public Future<Object> call(String operation, JsonObject args) {
        try {
            return switch (operation) {
                case "kv.get" -> kv.get(tenant, namespace(args), key(args));
                case "kv.set" -> withRoom(() -> kv.set(tenant, namespace(args), key(args), value(args, "value"), ttl(args)).map(v -> null));
                case "kv.delete" -> kv.delete(tenant, namespace(args), key(args)).map(Object.class::cast);
                case "kv.list" -> kv.list(tenant, namespace(args), prefix(args), limit(args), args.getString("cursor")).map(Object.class::cast);
                case "kv.increment" -> withRoom(() -> kv.increment(tenant, namespace(args), key(args), by(args)).map(Object.class::cast));
                case "files.stat" -> filesOrFail().stat(tenant, path(args)).map(info -> info == null ? null : (Object) info.toViewJson());
                case "files.list" -> filesOrFail().list(tenant, prefix(args), limit(args), args.getString("cursor")).map(Object.class::cast);
                case "files.delete" -> filesOrFail().delete(tenant, path(args)).map(Object.class::cast);
                case "bus.publish" -> publish(args);
                case "bus.request" -> request(args);
                default -> Future.failedFuture(new HostException("Error", "unknown_operation", "Unknown host operation " + operation));
            };
        } catch (HostException e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<Registration> listen(String kind, JsonObject args, Consumer<Inbound> listener) {
        try {
            return switch (kind) {
                case "bus.subscribe" -> consume(Addresses.topic(tenant, topic(args)), false, listener);
                case "bus.handle" -> consume(Addresses.handler(tenant, topic(args)), true, listener);
                default -> Future.failedFuture(new HostException("Error", "unknown_operation", "Unknown listener kind " + kind));
            };
        } catch (HostException e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<automately.core.files.FileWriter> createFile(JsonObject args) {
        try {
            String path = path(args);
            return capacity.ensureRoom()
                    .compose(room -> filesOrFail().create(tenant, path, args.getString("contentType")))
                    .map(writer -> {
                        openWriters.add(writer);
                        return writer;
                    });
        } catch (HostException e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<FetchedResponse> openFile(JsonObject args) {
        try {
            String path = path(args);
            return filesOrFail().stat(tenant, path).compose(info -> info == null
                    ? Future.failedFuture(new HostException("Error", "not_found", "No such file: " + path))
                    : Future.succeededFuture(new FetchedResponse(info.toViewJson(), files.open(info, 0, info.size() - 1))));
        } catch (HostException e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<Void> respondFile(HttpExchange exchange, JsonObject args) {
        try {
            String path = path(args);
            JsonObject request = exchange.request();
            return FileResponder.respond(filesOrFail(), tenant, path, request.getString("method"), request.getJsonObject("headers", new JsonObject()),
                    args.getJsonObject("headers"), exchange);
        } catch (HostException e) {
            exchange.fail(500, e.getMessage());
            return Future.failedFuture(e);
        }
    }

    private FileStore filesOrFail() {
        if (files == null) {
            throw new HostException("Error", "unavailable", "Files are not available here");
        }
        return files;
    }

    private Future<Object> withRoom(java.util.function.Supplier<Future<Object>> write) {
        return capacity.ensureRoom().compose(v -> write.get());
    }

    private static String path(JsonObject args) {
        String path = args.getValue("path") instanceof String p ? p : null;
        String invalid = FileStore.invalidPath(path);
        if (invalid != null) {
            throw HostException.invalid(invalid);
        }
        return path;
    }

    @Override
    public Future<Void> close() {
        // Uploads a script never finished are discarded.
        openWriters.forEach(automately.core.files.FileWriter::abort);
        openWriters.clear();
        List<Future<Void>> undone = registrations.stream().map(Registration::cancel).toList();
        registrations.clear();
        return Future.join(undone).mapEmpty();
    }

    // --- bus ---

    private Future<Object> publish(JsonObject args) {
        String topic = topic(args);
        vertx.eventBus().publish(Addresses.topic(tenant, topic), envelope(topic, value(args, "message")));
        return Future.succeededFuture();
    }

    private Future<Object> request(JsonObject args) {
        String topic = topic(args);
        long timeout = Math.min(Math.max(1, args.getLong("timeoutMs", 10_000L)), MAX_REQUEST_TIMEOUT_MS);
        return vertx.eventBus().<JsonObject>request(Addresses.handler(tenant, topic), envelope(topic, value(args, "message")),
                        new DeliveryOptions().setSendTimeout(timeout))
                .map(reply -> reply.body().getValue("value"))
                .recover(err -> Future.failedFuture(replyError(topic, err)));
    }

    static JsonObject envelope(String topic, Object message) {
        return new JsonObject().put("topic", topic).put("message", message);
    }

    static HostException replyError(String topic, Throwable err) {
        if (err instanceof ReplyException reply) {
            return switch (reply.failureType()) {
                case NO_HANDLERS -> new HostException("Error", "no_handler", "Nothing handles topic \"" + topic + "\"");
                case TIMEOUT -> new HostException("TimeoutError", "timeout", "No reply on topic \"" + topic + "\" in time");
                default -> new HostException("Error", "handler_failed", reply.getMessage());
            };
        }
        return new HostException("Error", "bus_error", String.valueOf(err.getMessage()));
    }

    private Future<Registration> consume(String address, boolean replies, Consumer<Inbound> listener) {
        MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(address, message -> listener.accept(inbound(message, replies)));
        Registration registration = consumer::unregister;
        registrations.add(registration);
        return consumer.completion().map(registration);
    }

    private static Inbound inbound(Message<JsonObject> message, boolean replies) {
        return new Inbound() {
            @Override
            public Object payload() {
                return message.body();
            }

            @Override
            public boolean expectsReply() {
                return replies;
            }

            @Override
            public void reply(Object value) {
                message.reply(new JsonObject().put("value", value));
            }

            @Override
            public void fail(int code, String reason) {
                message.fail(code, reason);
            }
        };
    }

    // --- http ---

    /** At most {@value #MAX_CONCURRENT_FETCHES} fetches whose bodies are still open. */
    @Override
    public Future<FetchedResponse> fetch(JsonObject args) {
        if (fetchesInFlight.incrementAndGet() > MAX_CONCURRENT_FETCHES) {
            fetchesInFlight.decrementAndGet();
            return Future.failedFuture(HostException.limit("Too many concurrent fetches (limit " + MAX_CONCURRENT_FETCHES + ")"));
        }
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                fetchesInFlight.decrementAndGet();
            }
        };
        return fetch.fetch(args, release).onFailure(err -> release.run());
    }

    @Override
    public Future<Registration> serve(Consumer<HttpExchange> listener) {
        if (!service) {
            return Future.failedFuture(new HostException("Error", "not_a_service", "Only services can serve HTTP"));
        }
        if (!serve.register(jobId, listener)) {
            return Future.failedFuture(new HostException("Error", "already_serving", "ctx.serve was already called in this run"));
        }
        Registration registration = () -> {
            serve.unregister(jobId, listener);
            return Future.succeededFuture();
        };
        registrations.add(registration);
        return Future.succeededFuture(registration);
    }

    // --- validation ---

    private static String name(JsonObject args, String field, boolean allowEmpty) {
        Object value = args.getValue(field);
        if (!(value instanceof String s) || (!allowEmpty && s.isEmpty())) {
            throw HostException.invalid("\"" + field + "\" must be a " + (allowEmpty ? "" : "non-empty ") + "string");
        }
        if (s.indexOf(SharedDataKvStore.SEPARATOR) >= 0) {
            throw HostException.invalid("\"" + field + "\" contains a forbidden character");
        }
        if (s.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            throw HostException.limit("\"" + field + "\" is over " + MAX_NAME_BYTES + " bytes");
        }
        return s;
    }

    private static String namespace(JsonObject args) {
        return name(args, "ns", false);
    }

    private static String key(JsonObject args) {
        return name(args, "key", false);
    }

    private static String prefix(JsonObject args) {
        return name(args, "prefix", true);
    }

    private static String topic(JsonObject args) {
        String topic = args.getString("topic", "");
        if (!topic.matches(TOPIC_PATTERN)) {
            throw HostException.invalid("A topic must match " + TOPIC_PATTERN);
        }
        return topic;
    }

    private static Object value(JsonObject args, String field) {
        Object value = args.getValue(field);
        if (Json.encode(value).getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            throw HostException.limit("The value is over " + MAX_VALUE_BYTES + " bytes of JSON");
        }
        return value;
    }

    private static Long ttl(JsonObject args) {
        Object ttl = args.getValue("ttlMs");
        if (ttl == null) {
            return null;
        }
        if (!(ttl instanceof Number n) || n.longValue() < 1) {
            throw HostException.invalid("\"ttlMs\" must be a positive number of milliseconds");
        }
        return n.longValue();
    }

    private static int limit(JsonObject args) {
        Object limit = args.getValue("limit");
        if (!(limit instanceof Number n) || n.intValue() < 1 || n.intValue() > MAX_LIST_LIMIT) {
            throw HostException.invalid("\"limit\" must be between 1 and " + MAX_LIST_LIMIT);
        }
        return n.intValue();
    }

    private static long by(JsonObject args) {
        Object by = args.getValue("by");
        if (!(by instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            throw HostException.invalid("\"by\" must be an integer");
        }
        return n.longValue();
    }
}
