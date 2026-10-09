package automately.core.packages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds what a script imports, without running it (D-087, D-089). Everything a job imports is fetched before it starts,
 * so the server reads a script's static imports and its {@code import('literal')} calls when the job is submitted, and
 * refuses an {@code import()} whose name it can't see.
 *
 * <p>It's a tokenizer, not a parser: it skips strings, comments, templates and regular expressions, and recognises the
 * import and export forms. Whether a {@code /} starts a regular expression is decided by the token before it, as
 * JavaScript tools commonly do; code that fools that is very unusual, and whatever this misses still can't load
 * anything that isn't in the job's lock.
 *
 * <p>It also rewrites what the isolate can't do itself (Javet 6 supports neither {@code import()} nor
 * {@code import.meta}): see {@link #rewrite}.
 */
public final class Imports {

    /** Modules the isolate provides itself (D-079, D-090); they're never packages. */
    public static final Set<String> BUILT_INS = Set.of("fs", "node:fs", "fs/promises", "node:fs/promises", "automately:fs",
            // Stand-ins for Node's own (D-090).
            "path", "node:path", "path/posix", "node:path/posix", "events", "node:events", "buffer", "node:buffer", "url", "node:url",
            "util", "node:util", "process", "node:process", "crypto", "node:crypto",
            "stream", "node:stream", "stream/promises", "node:stream/promises", "string_decoder", "node:string_decoder",
            "zlib", "node:zlib", "assert", "node:assert", "assert/strict", "node:assert/strict", "querystring", "node:querystring",
            "timers", "node:timers", "timers/promises", "node:timers/promises", "os", "node:os",
            // Phase B (D-109): the HTTP client, through the egress gate.
            "http", "node:http", "https", "node:https",
            // Sockets (D-116), the HTTP/2 client (D-117), and tty's pure parts.
            "net", "node:net", "tls", "node:tls", "http2", "node:http2", "tty", "node:tty",
            "diagnostics_channel", "node:diagnostics_channel", "util/types", "node:util/types",
            // Its names only: starting a process throws (D-116).
            "child_process", "node:child_process",
            // Through the egress gate's resolver (D-122).
            "dns", "node:dns", "dns/promises", "node:dns/promises",
            // For servers' libraries (D-123).
            "async_hooks", "node:async_hooks", "perf_hooks", "node:perf_hooks",
            // Their names only, for what libraries read when they load: no threads, no loading code, no V8 internals (D-123).
            "worker_threads", "node:worker_threads", "module", "node:module", "v8", "node:v8",
            // The WHATWG streams (D-124).
            "stream/web", "node:stream/web",
            // The host APIs as modules (D-091).
            "automately:kv", "automately:bus", "automately:jobs", "automately:files", "automately:secrets", "automately:fetch", "automately:log");

    /** Node's own modules, to say why one can't be imported instead of "no package named path". */
    private static final Set<String> NODE_MODULES = Set.of("assert", "async_hooks", "buffer", "child_process", "cluster", "console", "constants",
            "crypto", "dgram", "diagnostics_channel", "dns", "domain", "events", "http", "http2", "https", "inspector", "module", "net", "os",
            "path", "perf_hooks", "process", "punycode", "querystring", "readline", "repl", "stream", "string_decoder", "sys", "timers",
            "tls", "trace_events", "tty", "url", "util", "v8", "vm", "wasi", "worker_threads", "zlib");

    /** A cheap first look: only sources that might use {@code import()} or {@code import.meta} are tokenized to rewrite. */
    private static final Pattern MAYBE_REWRITE = Pattern.compile("\\bimport\\s*[(.]");

    /** Keywords after which a {@code /} starts a regular expression rather than dividing. */
    private static final Set<String> BEFORE_EXPRESSION = Set.of("return", "typeof", "instanceof", "in", "of", "new", "delete", "void",
            "throw", "case", "do", "else", "yield", "await", "extends");

    private Imports() {
    }

    /** One {@code import('name')} with a literal name: where it is in the source, and the name. */
    public record Dynamic(int start, int end, String specifier) {
    }

    /**
     * What a source imports.
     *
     * @param specifiers every name imported, statically or by a literal {@code import()}, each once, in order
     * @param dynamic    the literal {@code import()} calls
     * @param computed   offsets of {@code import()} calls whose name isn't a plain string literal
     * @param meta       {@code [start, end)} of each {@code import.meta}
     */
    public record Scan(List<String> specifiers, List<Dynamic> dynamic, List<Integer> computed, List<int[]> meta) {
    }

    // --- names ---

    /** Whether a name is relative, absolute or a URL: anything but a bare package or built-in name. */
    public static boolean isPath(String specifier) {
        return specifier.startsWith("./") || specifier.startsWith("../") || specifier.equals(".") || specifier.equals("..")
                || specifier.startsWith("/") || specifier.contains(":") && !specifier.startsWith("node:") && !specifier.startsWith("automately:");
    }

    /** The package a bare name imports: {@code strings/report} → {@code strings}, {@code @cluster/x/y} → {@code @cluster/x}. */
    public static String packageName(String specifier) {
        int slash = specifier.indexOf('/', specifier.startsWith("@") ? specifier.indexOf('/') + 1 : 0);
        return slash < 0 ? specifier : specifier.substring(0, slash);
    }

    /** The export a bare name asks for: {@code "."} for the package itself, {@code "./report"} for {@code strings/report}. */
    public static String subpath(String specifier) {
        String name = packageName(specifier);
        return specifier.length() == name.length() ? "." : "." + specifier.substring(name.length());
    }

    /** Why a name that isn't a built-in can't be a package, or null if it can. */
    public static String notAPackage(String specifier) {
        String bare = specifier.startsWith("node:") ? specifier.substring(5) : specifier;
        String module = bare.contains("/") ? bare.substring(0, bare.indexOf('/')) : bare;
        if (specifier.startsWith("node:") || NODE_MODULES.contains(module)) {
            return "Node's \"" + module + "\" module isn't available in the isolate (fs, path, events, buffer, url, util, process, crypto, stream, string_decoder, zlib, assert, querystring, timers, os, http, https, net, tls, http2, tty, diagnostics_channel, dns and child_process (names only) are; see docs/scripting/node-compat.md); run code that needs it with \"runtime\": \"node\"";
        }
        if (specifier.startsWith("automately:")) {
            return "There's no built-in module named \"" + specifier + "\"";
        }
        if (isPath(specifier)) {
            return "\"" + specifier + "\" is a path or URL; scripts import packages by name";
        }
        return PackageStore.invalidName(packageName(specifier));
    }

    /**
     * The packages a script imports, by name, from {@link Scan#specifiers()}: built-ins left out, and each package with
     * the exports it's imported for. Fails with the first name that can't be a package.
     */
    public static Map<String, Set<String>> packages(Scan scan) {
        Map<String, Set<String>> wanted = new LinkedHashMap<>();
        for (String specifier : scan.specifiers()) {
            if (BUILT_INS.contains(specifier)) {
                continue;
            }
            String why = notAPackage(specifier);
            if (why != null) {
                throw new IllegalArgumentException(why);
            }
            wanted.computeIfAbsent(packageName(specifier), n -> new LinkedHashSet<>()).add(subpath(specifier));
        }
        return wanted;
    }

    // --- scanning ---

    public static Scan scan(String source) {
        List<Token> tokens = tokenize(source);
        Set<String> specifiers = new LinkedHashSet<>();
        List<Dynamic> dynamic = new ArrayList<>();
        List<Integer> computed = new ArrayList<>();
        List<int[]> meta = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind != Kind.NAME || i > 0 && tokens.get(i - 1).is(".")) {
                continue;
            }
            if (t.text.equals("import")) {
                Token next = at(tokens, i + 1);
                if (next == null) {
                    continue;
                }
                if (next.is("(")) {
                    Token name = at(tokens, i + 2);
                    Token close = at(tokens, i + 3);
                    if (name != null && name.kind == Kind.STRING && close != null && close.is(")")) {
                        specifiers.add(name.value);
                        dynamic.add(new Dynamic(t.start, close.end, name.value));
                    } else {
                        computed.add(t.start);
                    }
                } else if (next.is(".")) {
                    Token property = at(tokens, i + 2);
                    if (property != null && property.kind == Kind.NAME && property.text.equals("meta")) {
                        meta.add(new int[]{t.start, property.end});
                    }
                } else {
                    String from = importFrom(tokens, i + 1);
                    if (from != null) {
                        specifiers.add(from);
                    }
                }
            } else if (t.text.equals("export")) {
                String from = exportFrom(tokens, i + 1);
                if (from != null) {
                    specifiers.add(from);
                }
            }
        }
        return new Scan(List.copyOf(specifiers), dynamic, computed, meta);
    }

    /** {@code import 'x'}, {@code import a from 'x'}, {@code import {…} from 'x'}, {@code import * as a from 'x'}, {@code import a, {…} from 'x'}. */
    private static String importFrom(List<Token> tokens, int i) {
        Token t = at(tokens, i);
        if (t == null) {
            return null;
        }
        if (t.kind == Kind.STRING) {
            return t.value;
        }
        if (t.kind == Kind.NAME) {
            Token after = at(tokens, i + 1);
            if (after != null && after.is(",")) {
                i += 2;
            } else {
                return from(tokens, i + 1);
            }
        }
        return clause(tokens, i);
    }

    /** {@code export * from 'x'}, {@code export * as a from 'x'}, {@code export {…} from 'x'}. */
    private static String exportFrom(List<Token> tokens, int i) {
        Token t = at(tokens, i);
        return t != null && (t.is("*") || t.is("{")) ? clause(tokens, i) : null;
    }

    /** {@code {…} from 'x'} or {@code * as a from 'x'} (and {@code * from 'x'} after export), starting at the brace or star. */
    private static String clause(List<Token> tokens, int i) {
        Token t = at(tokens, i);
        if (t == null) {
            return null;
        }
        if (t.is("{")) {
            int depth = 0;
            for (int j = i; j < tokens.size() && j < i + 10_000; j++) {
                Token u = tokens.get(j);
                if (u.is("{")) {
                    depth++;
                } else if (u.is("}") && --depth == 0) {
                    return from(tokens, j + 1);
                }
            }
            return null;
        }
        if (t.is("*")) {
            Token as = at(tokens, i + 1);
            if (as != null && as.kind == Kind.NAME && as.text.equals("as")) {
                return from(tokens, i + 3);
            }
            return from(tokens, i + 1);
        }
        return null;
    }

    private static String from(List<Token> tokens, int i) {
        Token from = at(tokens, i);
        Token name = at(tokens, i + 1);
        return from != null && from.kind == Kind.NAME && from.text.equals("from") && name != null && name.kind == Kind.STRING ? name.value : null;
    }

    private static Token at(List<Token> tokens, int i) {
        return i < tokens.size() ? tokens.get(i) : null;
    }

    // --- rewriting ---

    /**
     * Rewrites what Javet's isolate doesn't support (D-089), keeping every line where it was:
     * <ul>
     *   <li>{@code import('x')} with a literal name becomes {@code Promise.resolve(ns)}, where {@code ns} is a static
     *       {@code import * as} added at the end. The module is loaded with the others before the script starts,
     *       which the job's lock requires anyway; the difference is that it's evaluated up front, not when called.</li>
     *   <li>{@code import.meta} becomes this module's own object, {@code {url}}.</li>
     * </ul>
     * A computed {@code import()} is left alone, and rejects when called. Returns the source unchanged if there's
     * nothing to rewrite.
     */
    public static String rewrite(String source, String url) {
        if (!MAYBE_REWRITE.matcher(source).find()) {
            return source;
        }
        Scan scan = scan(source);
        if (scan.dynamic().isEmpty() && scan.meta().isEmpty()) {
            return source;
        }
        record Edit(int start, int end, String text) {
        }
        List<Edit> edits = new ArrayList<>();
        Map<String, String> namespaces = new LinkedHashMap<>();
        for (Dynamic d : scan.dynamic()) {
            String ns = namespaces.computeIfAbsent(d.specifier(), s -> "__automately_import_" + namespaces.size());
            edits.add(new Edit(d.start(), d.end(), "Promise.resolve(" + ns + ")"));
        }
        for (int[] m : scan.meta()) {
            edits.add(new Edit(m[0], m[1], "__automately_meta()"));
        }
        edits.sort(java.util.Comparator.comparingInt(Edit::start));
        StringBuilder out = new StringBuilder(source.length() + 256);
        int at = 0;
        for (Edit edit : edits) {
            out.append(source, at, edit.start()).append(edit.text());
            at = edit.end();
        }
        out.append(source, at, source.length()).append("\n;");
        namespaces.forEach((specifier, ns) -> out.append("import * as ").append(ns).append(" from ").append(quote(specifier)).append(';'));
        if (!scan.meta().isEmpty()) {
            out.append("function __automately_meta() { return __automately_meta.value ??= { url: ").append(quote(url)).append(" }; }");
        }
        return out.append('\n').toString();
    }

    private static String quote(String s) {
        return io.vertx.core.json.Json.encode(s);
    }

    // --- the tokenizer ---

    private enum Kind { NAME, STRING, NUMBER, PUNCT, TEMPLATE, REGEX }

    private record Token(Kind kind, String text, String value, int start, int end) {
        boolean is(String punct) {
            return kind == Kind.PUNCT && text.equals(punct);
        }
    }

    private static List<Token> tokenize(String s) {
        List<Token> tokens = new ArrayList<>();
        // For each open template expression (`${`), the brace depth it started at, so its `}` resumes the template.
        java.util.ArrayDeque<Integer> templates = new java.util.ArrayDeque<>();
        int depth = 0;
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '﻿') {
                i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                i = lineEnd(s, i);
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int close = s.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 2;
            } else if (c == '#' && i == 0 && n > 1 && s.charAt(1) == '!') {
                i = lineEnd(s, i);
            } else if (c == '\'' || c == '"') {
                StringBuilder value = new StringBuilder();
                int end = string(s, i, c, value);
                tokens.add(new Token(Kind.STRING, s.substring(i, end), value.toString(), i, end));
                i = end;
            } else if (c == '`') {
                i = template(s, i + 1, tokens, i);
                if (i < 0) {
                    // The template opened an expression: `${`.
                    i = -i;
                    templates.push(depth);
                    depth++;
                }
            } else if (c == '}' && !templates.isEmpty() && templates.peek() == depth - 1) {
                templates.pop();
                depth--;
                int start = i;
                i = template(s, i + 1, tokens, start);
                if (i < 0) {
                    i = -i;
                    templates.push(depth);
                    depth++;
                }
            } else if (Character.isJavaIdentifierStart(c) || c == '\\') {
                int start = i;
                i++;
                while (i < n && (Character.isJavaIdentifierPart(s.charAt(i)) || s.charAt(i) == '\\')) {
                    i++;
                }
                String text = s.substring(start, i);
                tokens.add(new Token(Kind.NAME, text, text, start, i));
            } else if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1))) {
                int start = i;
                i++;
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(Kind.NUMBER, s.substring(start, i), null, start, i));
            } else if (c == '/' && regexAllowed(tokens)) {
                int end = regex(s, i);
                tokens.add(new Token(Kind.REGEX, s.substring(i, end), null, i, end));
                i = end;
            } else {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth = Math.max(0, depth - 1);
                }
                tokens.add(new Token(Kind.PUNCT, String.valueOf(c), null, i, i + 1));
                i++;
            }
        }
        return tokens;
    }

    private static int lineEnd(String s, int i) {
        while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r' && s.charAt(i) != ' ' && s.charAt(i) != ' ') {
            i++;
        }
        return i;
    }

    /** Reads a quoted string from {@code i} (the quote); returns the index after it, its value in {@code value}. */
    private static int string(String s, int i, char quote, StringBuilder value) {
        int n = s.length();
        i++;
        while (i < n) {
            char c = s.charAt(i);
            if (c == quote) {
                return i + 1;
            }
            if (c == '\n' || c == '\r') {
                // Unterminated: a syntax error the isolate will report.
                return i;
            }
            if (c == '\\' && i + 1 < n) {
                char e = s.charAt(i + 1);
                switch (e) {
                    case 'n' -> value.append('\n');
                    case 't' -> value.append('\t');
                    case 'r' -> value.append('\r');
                    case '0' -> value.append('\0');
                    case 'u' -> {
                        int end = i + 2;
                        if (end < n && s.charAt(end) == '{') {
                            int close = s.indexOf('}', end);
                            if (close > 0) {
                                try {
                                    value.appendCodePoint(Integer.parseInt(s.substring(end + 1, close), 16));
                                } catch (IllegalArgumentException ignored) {
                                    // A syntax error the isolate will report.
                                }
                                i = close + 1;
                                continue;
                            }
                        } else if (end + 4 <= n) {
                            try {
                                value.append((char) Integer.parseInt(s.substring(end, end + 4), 16));
                            } catch (NumberFormatException ignored) {
                                // A syntax error the isolate will report.
                            }
                            i = end + 4;
                            continue;
                        }
                        value.append(e);
                    }
                    case '\r', '\n' -> {
                        // A line continuation adds nothing.
                    }
                    default -> value.append(e);
                }
                i += 2;
                continue;
            }
            value.append(c);
            i++;
        }
        return n;
    }

    /**
     * Reads template text from {@code i} (just after a backtick or a closing brace). Returns the index after the closing
     * backtick, or minus the index after a {@code ${}, which opens an expression.
     */
    private static int template(String s, int i, List<Token> tokens, int start) {
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '`') {
                tokens.add(new Token(Kind.TEMPLATE, "`", null, start, i + 1));
                return i + 1;
            } else if (c == '$' && i + 1 < n && s.charAt(i + 1) == '{') {
                tokens.add(new Token(Kind.TEMPLATE, "${", null, start, i + 2));
                return -(i + 2);
            } else {
                i++;
            }
        }
        tokens.add(new Token(Kind.TEMPLATE, "`", null, start, n));
        return n;
    }

    private static boolean regexAllowed(List<Token> tokens) {
        if (tokens.isEmpty()) {
            return true;
        }
        Token last = tokens.getLast();
        return switch (last.kind) {
            case NAME -> BEFORE_EXPRESSION.contains(last.text);
            case STRING, NUMBER, REGEX -> false;
            // A template that ends (`) is a value; one that opens an expression (${) is followed by one.
            case TEMPLATE -> last.text.equals("${");
            case PUNCT -> !(last.is(")") || last.is("]"));
        };
    }

    /** Reads a regular expression literal from {@code i} (the slash); returns the index after its flags. */
    private static int regex(String s, int i) {
        int n = s.length();
        boolean inClass = false;
        i++;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '\n' || c == '\r') {
                return i;
            }
            if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '/' && !inClass) {
                i++;
                while (i < n && Character.isJavaIdentifierPart(s.charAt(i))) {
                    i++;
                }
                return i;
            }
            i++;
        }
        return n;
    }
}
