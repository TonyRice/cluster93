package cluster93.core.host.egress;

/** What happens to a connection no rule decided (D-111). */
public enum Posture {
    /** Public addresses are allowed; private and reserved ones refused. The default. */
    PUBLIC("public"),
    /** Everything is refused unless a rule allows it. */
    ALLOW_LIST("allow-list");

    public final String code;

    Posture(String code) {
        this.code = code;
    }

    static Posture parse(Object value) {
        for (Posture posture : values()) {
            if (posture.code.equals(value)) {
                return posture;
            }
        }
        throw new IllegalArgumentException("\"posture\" is \"public\" or \"allow-list\"");
    }
}
