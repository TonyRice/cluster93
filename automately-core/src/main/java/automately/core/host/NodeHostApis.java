package automately.core.host;

import automately.core.files.FileStore;
import automately.core.files.Room;
import automately.core.files.StorageCapacity;
import automately.core.runtime.api.BusApi;
import automately.core.runtime.api.FetchApi;
import automately.core.runtime.api.FilesApi;
import automately.core.runtime.api.HostApis;
import automately.core.runtime.api.HttpApi;
import automately.core.runtime.api.JobsApi;
import automately.core.runtime.api.KvApi;
import automately.core.runtime.api.QueueApi;
import automately.core.storage.TenantQuota;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

/** The host APIs of one execution on this node, all sharing one {@link HostScope} (docs/design/host-apis.md). */
public final class NodeHostApis implements HostApis {

    private final HostScope scope;
    private final KvApi kv;
    private final BusApi bus;
    private final QueueApi queue;
    private final JobsApi jobs;
    private final FilesApi files;
    private final FetchApi fetch;
    private final HttpApi http;

    /** The node's shared stores and clients; one per node. */
    public record Node(Vertx vertx, KvStore kv, FetchClient fetch, ServeRegistry serve, FileStore files, StorageCapacity capacity,
                       TenantQuota quota, Room room, automately.core.queue.QueueService queues, automately.core.job.JobStore jobs) {

        /** Without files, queues, jobs, storage limits or quotas: for tests. */
        public Node(Vertx vertx, KvStore kv, FetchClient fetch, ServeRegistry serve) {
            this(vertx, kv, fetch, serve, null, StorageCapacity.UNLIMITED, TenantQuota.unlimited(), Room.fixed(Long.MAX_VALUE), null, null);
        }
    }

    public NodeHostApis(Node node, String tenant, String jobId, boolean service) {
        this.scope = new HostScope(node.vertx(), tenant, jobId, service);
        this.kv = new NodeKvApi(scope, node.kv(), node.capacity(), node.quota());
        this.bus = new NodeBusApi(scope);
        this.queue = new NodeQueueApi(scope, node.queues());
        this.jobs = new NodeJobsApi(scope, node.jobs());
        this.files = new NodeFilesApi(scope, node.files(), node.capacity(), node.quota(), node.room());
        this.fetch = new NodeFetchApi(node.fetch());
        this.http = new NodeHttpApi(scope, node.serve());
    }

    /** Builds each execution's APIs over one node's stores. */
    public static HostApisFactory factory(Node node) {
        return (tenant, jobId, service) -> new NodeHostApis(node, tenant, jobId, service);
    }

    @Override
    public KvApi kv() {
        return kv;
    }

    @Override
    public BusApi bus() {
        return bus;
    }

    @Override
    public QueueApi queue() {
        return queue;
    }

    @Override
    public JobsApi jobs() {
        return jobs;
    }

    @Override
    public FilesApi files() {
        return files;
    }

    @Override
    public FetchApi fetch() {
        return fetch;
    }

    @Override
    public HttpApi http() {
        return http;
    }

    @Override
    public Future<Void> close() {
        return scope.close();
    }
}
