package automately.core.host;

/** Event-bus addresses for tenant topics and queues. */
public final class Addresses {

    private Addresses() {
    }

    /** Where {@code bus.publish} sends and {@code bus.subscribe} listens. */
    public static String topic(String tenant, String topic) {
        return "automately.t." + tenant + ".bus." + topic;
    }

    /** Where {@code bus.request} sends and {@code bus.handle} listens. */
    public static String handler(String tenant, String topic) {
        return "automately.t." + tenant + ".bus-handle." + topic;
    }

    /** Published to when a message is pushed, so idle consumers of the queue claim it at once (D-061). */
    public static String queueHint(String tenant, String queue) {
        return "automately.t." + tenant + ".queue." + queue;
    }
}
