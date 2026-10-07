package automately.core.runtime.api;

import automately.core.runtime.Inbound;
import io.vertx.core.Future;

import java.util.function.Consumer;

/**
 * Messaging between scripts in the tenant, across the cluster: fire-and-forget fan-out and request/reply. Delivery is
 * at most once; a message nobody listens for is dropped. Inbound payloads are {@code {topic, message}}.
 */
public interface BusApi {

    String TOPIC_PATTERN = "[a-zA-Z0-9._-]{1,128}";
    int MAX_MESSAGE_BYTES = 256 * 1024;
    long MAX_REQUEST_TIMEOUT_MS = 30_000;

    Future<Void> publish(String topic, Object message);

    /** The answer of the topic's handler. */
    Future<Object> request(String topic, Object message, long timeoutMs);

    /** Every message published to the topic. */
    Future<Registration> subscribe(String topic, Consumer<Inbound> listener);

    /** Requests to the topic; each expects an answer. */
    Future<Registration> handle(String topic, Consumer<Inbound> listener);
}
