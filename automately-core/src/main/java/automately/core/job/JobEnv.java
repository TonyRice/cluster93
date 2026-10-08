package automately.core.job;

import automately.core.runtime.HostException;
import automately.core.secrets.TenantSecrets;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A job's {@code process.env}, declared with the job (D-095): {@code {"API_URL": "https://…", "KEY": {"secret": "name"}}}.
 * Nothing is implicit: the script sees exactly what's declared, plus {@code NODE_ENV}, never the node's environment.
 * <ul>
 *   <li>A secret is named, never stored: the job record keeps {@code {secret: name}}, and the node reads the value when
 *       the run starts, by the same rule as {@code secrets.get} (D-068).</li>
 *   <li>Secret values that turn up in the job's log are masked as {@code [secret:<name>]} ({@link Resolved#mask}).</li>
 * </ul>
 */
public final class JobEnv {

    public static final int MAX_ENTRIES = 100;
    public static final int MAX_VALUE_CHARS = 32 * 1024;
    /** Shorter secret values aren't masked: masking "a" would garble every line. */
    static final int MIN_MASKED_CHARS = 4;
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    private JobEnv() {
    }

    /** Null if {@code env} is a valid declaration (or null); otherwise why not. */
    public static String invalid(Object env) {
        if (env == null) {
            return null;
        }
        if (!(env instanceof JsonObject declared)) {
            return "\"env\" must map names to values: {\"API_URL\": \"https://…\", \"KEY\": {\"secret\": \"name\"}}";
        }
        if (declared.size() > MAX_ENTRIES) {
            return "\"env\" can have at most " + MAX_ENTRIES + " entries";
        }
        for (String name : declared.fieldNames()) {
            if (!NAME.matcher(name).matches()) {
                return "\"" + name + "\" isn't an environment variable name: a letter or '_', then letters, digits or '_'";
            }
            Object value = declared.getValue(name);
            if (value instanceof String text) {
                if (text.length() > MAX_VALUE_CHARS) {
                    return "env." + name + " is longer than " + MAX_VALUE_CHARS + " characters";
                }
            } else if (value instanceof JsonObject ref && ref.size() == 1 && ref.getValue("secret") instanceof String secret) {
                if (!TenantSecrets.validName(secret)) {
                    return "env." + name + " names \"" + secret + "\", which isn't a secret's name";
                }
            } else {
                return "env." + name + " must be a string, or {\"secret\": \"name\"}";
            }
        }
        return null;
    }

    /** Whether reading it needs {@code secrets:use}. */
    public static boolean usesSecrets(JsonObject env) {
        return env != null && env.stream().anyMatch(e -> e.getValue() instanceof JsonObject);
    }

    /**
     * What the script sees: the values, with each secret read, and those secrets' values for masking.
     *
     * @param secretsByValue a secret's value → its name, longest first, so a value inside another is masked as the longer
     */
    public record Resolved(Map<String, String> values, Map<String, String> secretsByValue) {

        public static final Resolved NONE = new Resolved(Map.of("NODE_ENV", "production"), Map.of());

        /** A log line with every secret value in it replaced by {@code [secret:<name>]}. */
        public String mask(String line) {
            if (secretsByValue.isEmpty() || line == null) {
                return line;
            }
            String masked = line;
            for (Map.Entry<String, String> secret : secretsByValue.entrySet()) {
                masked = masked.replace(secret.getKey(), "[secret:" + secret.getValue() + "]");
            }
            return masked;
        }
    }

    /**
     * Reads the declared secrets for a run. Fails (the run doesn't start) if the job may not read secrets, if secrets
     * aren't set up, or if a named one doesn't exist.
     */
    public static Future<Resolved> resolve(JsonObject env, String tenant, boolean secretsAllowed, TenantSecrets secrets) {
        if (env == null || env.isEmpty()) {
            return Future.succeededFuture(Resolved.NONE);
        }
        if (usesSecrets(env)) {
            if (!secretsAllowed) {
                return Future.failedFuture(new HostException("Error", "forbidden",
                        "env names secrets, but this job may not read them: submit it with a token that has secrets:use"));
            }
            if (secrets == null || !secrets.available()) {
                return Future.failedFuture(new HostException("Error", "unavailable",
                        "env names secrets, but the cluster has no AUTOMATELY_SECRETS_KEY"));
            }
        }
        List<String> names = List.copyOf(env.fieldNames());
        List<Future<String>> values = new ArrayList<>();
        for (String name : names) {
            Object value = env.getValue(name);
            if (value instanceof JsonObject ref) {
                String secret = ref.getString("secret");
                values.add(secrets.value(tenant, secret).compose(found -> found != null ? Future.succeededFuture(found)
                        : Future.failedFuture(new HostException("Error", "not_found", "env." + name + " names the secret \"" + secret
                        + "\", which doesn't exist"))));
            } else {
                values.add(Future.succeededFuture(String.valueOf(value)));
            }
        }
        return Future.all(values).map(all -> {
            Map<String, String> resolved = new LinkedHashMap<>();
            resolved.put("NODE_ENV", "production");
            List<Map.Entry<String, String>> secretValues = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                resolved.put(names.get(i), values.get(i).result());
                if (env.getValue(names.get(i)) instanceof JsonObject ref && values.get(i).result().length() >= MIN_MASKED_CHARS) {
                    secretValues.add(Map.entry(values.get(i).result(), ref.getString("secret")));
                }
            }
            secretValues.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
            Map<String, String> byValue = new LinkedHashMap<>();
            secretValues.forEach(e -> byValue.putIfAbsent(e.getKey(), e.getValue()));
            return new Resolved(Map.copyOf(resolved), byValue);
        });
    }
}
