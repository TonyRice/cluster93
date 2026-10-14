package cluster93.server.api;

import java.util.regex.Pattern;

/** Rules for tenant and service names. They become DNS labels in automatic hosts, so no "--" (the separator). */
final class Names {

    static final String RULE = "lowercase letters, digits, '_' and '-', up to 63 characters, starting with a letter or digit, without \"--\"";
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,62}");
    private static final Pattern DOMAIN = Pattern.compile("(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

    private Names() {
    }

    static boolean isValid(String name) {
        return name != null && NAME.matcher(name).matches() && !name.contains("--");
    }

    static boolean isValidDomain(String domain) {
        return domain != null && DOMAIN.matcher(domain).matches();
    }
}
