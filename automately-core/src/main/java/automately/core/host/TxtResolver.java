package automately.core.host;

import io.vertx.core.Future;

import java.util.List;

/** Looks up DNS TXT records; pluggable so domain verification can be tested without real DNS. */
@FunctionalInterface
public interface TxtResolver {

    /** Completes with the TXT strings at {@code name}, or an empty list if there are none. */
    Future<List<String>> txt(String name);
}
