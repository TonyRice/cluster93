package automately.core.job;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Execution policy a job's script may declare for itself, so a submitter doesn't have to repeat it
 * (docs/design/orphaned-jobs.md). A script can export a single config object:
 *
 * <pre>{@code
 * export const config = {
 *   onOrphan: 'retry',
 *   maxAttempts: 3,
 *   timeoutMs: 30000,
 * };
 * }</pre>
 *
 * or export the settings on their own:
 *
 * <pre>{@code
 * export const onOrphan = 'resume';
 * export const maxAttempts = 3;
 * export const timeoutMs = 30000;
 * }</pre>
 *
 * <p>Explicit request options always win over what a script declares; a declaration is only a default.
 * Parsing is deliberately forgiving: comments, whitespace and trailing commas are fine, and an
 * unrecognised or out-of-range value is ignored rather than an error, because the script is also
 * ordinary JavaScript that the runtime will validate in full.
 *
 * @param onOrphan    the declared orphan policy, or null
 * @param maxAttempts the declared attempt limit, or null
 * @param timeoutMs   the declared per-attempt timeout in milliseconds, or null
 */
public record ScriptConfig(OrphanPolicy onOrphan, Integer maxAttempts, Long timeoutMs) {

    public static final ScriptConfig EMPTY = new ScriptConfig(null, null, null);

    /** Matches the declaration up to its opening brace; the body is then read by brace depth, not a regex. */
    private static final Pattern CONFIG_OBJECT_HEAD = Pattern.compile(
            "\\bexport\\s+(?:const|var|let)\\s+config\\s*=\\s*\\{", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBJECT_ON_ORPHAN = Pattern.compile(
            "(?:^|[,\\s{])[\"']?onOrphan[\"']?\\s*:\\s*[\"']([A-Za-z]+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBJECT_MAX_ATTEMPTS = Pattern.compile(
            "(?:^|[,\\s{])[\"']?maxAttempts[\"']?\\s*:\\s*(-?\\d[\\d_]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBJECT_TIMEOUT = Pattern.compile(
            "(?:^|[,\\s{])[\"']?timeoutMs[\"']?\\s*:\\s*(-?\\d[\\d_]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECT_ON_ORPHAN = Pattern.compile(
            "\\bexport\\s+(?:const|var|let)\\s+[\"']?onOrphan[\"']?\\s*=\\s*[\"']([A-Za-z]+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECT_MAX_ATTEMPTS = Pattern.compile(
            "\\bexport\\s+(?:const|var|let)\\s+[\"']?maxAttempts[\"']?\\s*=\\s*(-?\\d[\\d_]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECT_TIMEOUT = Pattern.compile(
            "\\bexport\\s+(?:const|var|let)\\s+[\"']?timeoutMs[\"']?\\s*=\\s*(-?\\d[\\d_]*)", Pattern.CASE_INSENSITIVE);

    /**
     * Reads whatever valid policy the script declares, or {@link #EMPTY} when it declares none (or only
     * invalid values). The script may be empty, as it is for a package submission.
     */
    public static ScriptConfig parse(String script) {
        if (script == null || script.isBlank()) {
            return EMPTY;
        }
        String text = stripComments(script);
        String body = configObjectBody(text);

        OrphanPolicy onOrphan = orphanPolicy(firstNonNull(group(OBJECT_ON_ORPHAN, body), group(DIRECT_ON_ORPHAN, text)));
        Integer maxAttempts = maxAttempts(firstNonNull(group(OBJECT_MAX_ATTEMPTS, body), group(DIRECT_MAX_ATTEMPTS, text)));
        Long timeoutMs = timeoutMs(firstNonNull(group(OBJECT_TIMEOUT, body), group(DIRECT_TIMEOUT, text)));
        if (onOrphan == null && maxAttempts == null && timeoutMs == null) {
            return EMPTY;
        }
        return new ScriptConfig(onOrphan, maxAttempts, timeoutMs);
    }

    private static String group(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * The inside of the {@code export const config = { ... }} object, or null when the script declares none.
     * The closing brace is found by tracking brace depth rather than with a regex, so a nested object or a
     * literal that contains a brace (a string, a template or a comment) doesn't end the object early.
     */
    static String configObjectBody(String text) {
        Matcher head = CONFIG_OBJECT_HEAD.matcher(text);
        if (!head.find()) {
            return null;
        }
        int start = head.end();
        int depth = 1;
        char quote = 0;
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\' && i + 1 < text.length()) {
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    quote = 0;
                }
                i++;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                quote = c;
                i++;
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < text.length() && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i);
                }
            }
            i++;
        }
        return null;
    }

    private static String firstNonNull(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static OrphanPolicy orphanPolicy(String value) {
        return value == null ? null : OrphanPolicy.fromApi(value.toLowerCase(Locale.ROOT));
    }

    private static Integer maxAttempts(String value) {
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.replace("_", ""));
            return parsed >= 1 && parsed <= OrphanPolicy.MAX_ATTEMPTS_LIMIT ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long timeoutMs(String value) {
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.replace("_", ""));
            return parsed >= 1 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Removes line and block comments so a commented-out declaration is not read as one, while leaving
     * string contents untouched (a string may contain {@code //}, as URLs do).
     */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        char quote = 0;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < source.length()) {
                    out.append(source.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    quote = 0;
                }
                i++;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                quote = c;
                out.append(c);
                i++;
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, source.length());
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
