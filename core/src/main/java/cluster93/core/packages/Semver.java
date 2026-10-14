package cluster93.core.packages;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Package versions and the ranges dependencies name (D-083): exact, {@code ^}, {@code ~} and {@code x} ranges, as npm
 * reads them, and nothing else. A range never matches a pre-release unless it names that exact version.
 */
public final class Semver {

    private static final Pattern VERSION = Pattern.compile(
            "(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");
    private static final Pattern PARTIAL = Pattern.compile("(0|[1-9]\\d{0,8}|[xX*])(?:\\.(0|[1-9]\\d{0,8}|[xX*]))?(?:\\.(0|[1-9]\\d{0,8}|[xX*]))?");

    private Semver() {
    }

    /** A version: {@code major.minor.patch}, optionally with a pre-release ({@code 1.0.0-beta.2}). No build metadata. */
    public record Version(int major, int minor, int patch, String pre) implements Comparable<Version> {

        /** Null if {@code text} isn't a version. */
        public static Version parse(String text) {
            Matcher m = text == null || text.length() > 64 ? null : VERSION.matcher(text);
            if (m == null || !m.matches()) {
                return null;
            }
            return new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), m.group(4));
        }

        @Override
        public int compareTo(Version other) {
            int c = Integer.compare(major, other.major);
            c = c != 0 ? c : Integer.compare(minor, other.minor);
            c = c != 0 ? c : Integer.compare(patch, other.patch);
            if (c != 0) {
                return c;
            }
            // A pre-release comes before its release; between pre-releases, field by field (semver.org §11).
            if (pre == null || other.pre == null) {
                return pre == null ? (other.pre == null ? 0 : 1) : -1;
            }
            String[] a = pre.split("\\.");
            String[] b = other.pre.split("\\.");
            for (int i = 0; i < Math.min(a.length, b.length); i++) {
                boolean an = a[i].chars().allMatch(Character::isDigit);
                boolean bn = b[i].chars().allMatch(Character::isDigit);
                int f = an && bn ? Long.compare(Long.parseLong(a[i]), Long.parseLong(b[i])) : an ? -1 : bn ? 1 : a[i].compareTo(b[i]);
                if (f != 0) {
                    return f;
                }
            }
            return Integer.compare(a.length, b.length);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch + (pre == null ? "" : "-" + pre);
        }
    }

    /** Versions from {@code min} (inclusive) up to {@code max} (exclusive, or none); or exactly {@code exact}. */
    public record Range(Version min, Version max, Version exact) {

        public boolean matches(Version v) {
            if (exact != null) {
                return exact.compareTo(v) == 0;
            }
            return v.pre() == null && (min == null || v.compareTo(min) >= 0) && (max == null || v.compareTo(max) < 0);
        }

        /**
         * Null if {@code text} isn't a range this understands: {@code 1.2.3}, {@code =1.2.3}, {@code ^1.2.3}, {@code ~1.2},
         * {@code 1.x}, {@code 1.2.*}, {@code *} or {@code ""}.
         */
        public static Range parse(String text) {
            if (text == null || text.length() > 64) {
                return null;
            }
            String t = text.strip();
            if (t.isEmpty() || t.equals("*") || t.equalsIgnoreCase("x")) {
                return new Range(null, null, null);
            }
            char op = t.charAt(0);
            if (op == '^' || op == '~' || op == '=') {
                t = t.substring(1).strip();
            } else {
                op = 0;
            }
            Version full = Version.parse(t);
            if (full != null) {
                if (op == 0 || op == '=' || full.pre() != null) {
                    return op == 0 || op == '=' ? new Range(null, null, full) : null;
                }
                return op == '^' ? caret(full.major(), full.minor(), full.patch(), 3) : tilde(full.major(), full.minor(), full.patch(), 3);
            }
            Matcher m = PARTIAL.matcher(t);
            if (!m.matches() || op == '=') {
                return null;
            }
            // How many leading parts are numbers; whatever follows a wildcard or is missing is a wildcard.
            int parts = 0;
            int[] n = new int[3];
            for (int i = 0; i < 3; i++) {
                String g = m.group(i + 1);
                if (g == null || g.equalsIgnoreCase("x") || g.equals("*")) {
                    break;
                }
                n[i] = Integer.parseInt(g);
                parts++;
            }
            if (parts == 0) {
                return new Range(null, null, null);
            }
            if (op == '^') {
                return caret(n[0], n[1], n[2], parts);
            }
            if (op == '~' || parts > 1) {
                return tilde(n[0], n[1], n[2], parts);
            }
            return new Range(new Version(n[0], 0, 0, null), new Version(n[0] + 1, 0, 0, null), null);
        }

        /** {@code ^}: changes that don't touch the leftmost non-zero part ({@code ^0.2.3} stays below 0.3.0). */
        private static Range caret(int major, int minor, int patch, int parts) {
            Version min = new Version(major, minor, patch, null);
            Version max;
            if (major > 0 || parts == 1) {
                max = new Version(major + 1, 0, 0, null);
            } else if (minor > 0 || parts == 2) {
                max = new Version(0, minor + 1, 0, null);
            } else {
                max = new Version(0, 0, patch + 1, null);
            }
            return new Range(min, max, null);
        }

        /** {@code ~}: patch changes if a minor is given, else minor changes. */
        private static Range tilde(int major, int minor, int patch, int parts) {
            Version min = new Version(major, minor, patch, null);
            Version max = parts == 1 ? new Version(major + 1, 0, 0, null) : new Version(major, minor + 1, 0, null);
            return new Range(min, max, null);
        }
    }
}
