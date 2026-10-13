package cluster93.core.host;

import cluster93.core.runtime.HttpExchange;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * On one node: the servers of the runs serving here, and where their requests go (D-112, D-123). A run serves on its
 * main server ({@code ctx.serve}) and on ports ({@code listen(port)}), all reached through the ingress by hostname:
 * nothing listens on the node. The service's host goes to its main server, or else to the first port that listened.
 */
public final class ServeRegistry {

    private static final class Servers {
        Consumer<HttpExchange> main;
        final Map<Integer, Consumer<HttpExchange>> ports = new LinkedHashMap<>();

        boolean empty() {
            return main == null && ports.isEmpty();
        }
    }

    private final Map<String, Servers> jobs = new ConcurrentHashMap<>();

    /** Returns false if the job already serves its main server. */
    boolean register(String jobId, Consumer<HttpExchange> handler) {
        return register(jobId, null, handler);
    }

    /** Registers a server on {@code port}, or the main one if null. Returns false if that one is already served. */
    boolean register(String jobId, Integer port, Consumer<HttpExchange> handler) {
        boolean[] added = {false};
        jobs.compute(jobId, (id, servers) -> {
            Servers all = servers == null ? new Servers() : servers;
            synchronized (all) {
                if (port == null ? all.main == null : !all.ports.containsKey(port)) {
                    if (port == null) {
                        all.main = handler;
                    } else {
                        all.ports.put(port, handler);
                    }
                    added[0] = true;
                }
            }
            return all;
        });
        return added[0];
    }

    void unregister(String jobId, Consumer<HttpExchange> handler) {
        unregister(jobId, null, handler);
    }

    void unregister(String jobId, Integer port, Consumer<HttpExchange> handler) {
        jobs.computeIfPresent(jobId, (id, servers) -> {
            synchronized (servers) {
                if (port == null ? servers.main == handler : servers.ports.get(port) == handler) {
                    if (port == null) {
                        servers.main = null;
                    } else {
                        servers.ports.remove(port);
                    }
                }
                return servers.empty() ? null : servers;
            }
        });
    }

    /** The job's main server: its {@code ctx.serve}, else the first port it listened on; null if it serves none. */
    public Consumer<HttpExchange> handler(String jobId) {
        Servers servers = jobs.get(jobId);
        if (servers == null) {
            return null;
        }
        synchronized (servers) {
            return servers.main != null ? servers.main : servers.ports.values().stream().findFirst().orElse(null);
        }
    }

    /** The job's server on {@code port}, or null if nothing listens there in this run. */
    public Consumer<HttpExchange> handler(String jobId, int port) {
        Servers servers = jobs.get(jobId);
        if (servers == null) {
            return null;
        }
        synchronized (servers) {
            return servers.ports.get(port);
        }
    }
}
