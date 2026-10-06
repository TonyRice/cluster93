package automately.core.host;

/** Event-bus addresses for tenant topics. */
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
}
