package automately.core.runtime.api;

import io.vertx.core.Future;

/**
 * Everything a script can do beyond computing, as one object per API (D-060). The node builds a set per execution,
 * already scoped to the job's tenant, so no method takes a tenant. Nothing here knows about a script engine: a
 * runtime binds these objects into its language (Javet into JavaScript today), converting arguments and turning each
 * {@link Future} into the language's own promise. Futures may complete on any thread.
 * See docs/design/host-apis.md.
 */
public interface HostApis {

    KvApi kv();

    BusApi bus();

    /** The bus's durable mode, {@code bus.queue(name)}. */
    QueueApi queue();

    /** The script's own job and the jobs it starts. */
    JobsApi jobs();

    FilesApi files();

    FetchApi fetch();

    HttpApi http();

    /** Stops every listener this execution registered and discards unfinished uploads. Called once the script has ended. */
    Future<Void> close();

    /** No host APIs: every call fails with {@code unavailable}. For tests and one-off runs. */
    HostApis NONE = Unavailable.APIS;
}
