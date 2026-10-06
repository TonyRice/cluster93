package automately.core.service;

import java.util.Locale;

/** When a service's run ends, should the cluster start it again? */
public enum RestartPolicy {
    /** Restart after any end except a stop. The default. */
    ALWAYS,
    /** Restart only after a failure; a run that returns normally completes the service. */
    ON_FAILURE;

    public String apiName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static RestartPolicy fromApi(String value) {
        for (RestartPolicy policy : values()) {
            if (policy.apiName().equals(value)) {
                return policy;
            }
        }
        return null;
    }
}
