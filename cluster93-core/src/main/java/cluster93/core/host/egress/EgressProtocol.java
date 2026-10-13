package cluster93.core.host.egress;

import java.util.Locale;

/** What kind of connection a rule applies to (D-111). */
public enum EgressProtocol {
    /** {@code fetch}, {@code http}, {@code https}, {@code http2}, and the host's own requests for a script (callbacks). */
    HTTP,
    /** {@code net} and {@code tls} sockets. */
    TCP,
    /** {@code dns} lookups. */
    DNS;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    static EgressProtocol parse(String text) {
        for (EgressProtocol protocol : values()) {
            if (protocol.code().equals(text)) {
                return protocol;
            }
        }
        throw new IllegalArgumentException("Unknown protocol \"" + text + "\": use http, tcp or dns");
    }
}
