package automately.core.host.dns;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Service names for ports, from the system's {@code /etc/services} (tcp), as getnameinfo gives them to Node. */
public final class Services {

    private static final class Table {
        static final Map<Integer, String> TCP = load();
    }

    private Services() {
    }

    /** The service on {@code port}, or the port as text if the system names none. */
    public static String name(int port) {
        return Table.TCP.getOrDefault(port, String.valueOf(port));
    }

    private static Map<Integer, String> load() {
        Map<Integer, String> out = new HashMap<>();
        try {
            for (String line : Files.readAllLines(Path.of("/etc/services"))) {
                int hash = line.indexOf('#');
                String[] fields = (hash >= 0 ? line.substring(0, hash) : line).trim().split("\\s+");
                if (fields.length >= 2 && fields[1].endsWith("/tcp")) {
                    try {
                        out.putIfAbsent(Integer.parseInt(fields[1].substring(0, fields[1].length() - 4)), fields[0]);
                    } catch (NumberFormatException e) {
                        // not a port
                    }
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            // no services file: ports answer as numbers
        }
        return Map.copyOf(out);
    }
}
