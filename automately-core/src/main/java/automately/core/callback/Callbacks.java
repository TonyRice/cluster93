package automately.core.callback;

import automately.core.runtime.HostException;
import io.vertx.core.json.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Completion callbacks (D-062): when a job ends, its result is POSTed to the URL it was given, signed with the
 * tenant's callback secret. The rules shared by the API, scripts and delivery.
 */
public final class Callbacks {

    /** The system queue deliveries go through, so they're retried and survive nodes dying (D-061). */
    public static final String QUEUE = "callbacks";
    /** Deliveries per callback: 1 s, 4 s, 16 s, 64 s, 256 s, then every 10 min; about half an hour in all. */
    public static final int MAX_DELIVERIES = 8;
    public static final int MAX_URL_LENGTH = 2048;
    public static final int MAX_DATA_BYTES = 16 * 1024;
    public static final long TIMEOUT_MS = 10_000;
    public static final String SIGNATURE_HEADER = "Automately-Signature";

    private Callbacks() {
    }

    /** Checks a callback before it's stored; the address itself is checked against the egress policy when it's used. */
    public static void validate(String url, Object data) {
        if (url == null || url.length() > MAX_URL_LENGTH) {
            throw HostException.invalid("A callback URL must be a string of at most " + MAX_URL_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw HostException.invalid("The callback URL isn't a valid URL");
        }
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")) || uri.getHost() == null) {
            throw HostException.invalid("A callback URL must be http:// or https:// with a host");
        }
        if (Json.encode(data).getBytes(StandardCharsets.UTF_8).length > MAX_DATA_BYTES) {
            throw HostException.limit("Callback data is over " + MAX_DATA_BYTES + " bytes of JSON");
        }
    }

    /** {@code t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}, as receivers check it. */
    public static String signature(String secret, long unixSeconds, String body) {
        return "t=" + unixSeconds + ",v1=" + hmac(secret, unixSeconds + "." + body);
    }

    static String hmac(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
