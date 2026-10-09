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
import automately.core.runtime.api.SecretsApi;
import automately.core.secrets.TenantSecrets;
import automately.core.storage.TenantQuota;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

/** The host APIs of one execution on this node, all sharing one {@link HostScope} (docs/design/host-apis.md). */
public final class NodeHostApis implements HostApis {

    private final automately.core.runtime.api.FsApi fs;
    private final HostScope scope;
    private final KvApi kv;
    private final BusApi bus;
    private final QueueApi queue;
    private final JobsApi jobs;
    private final FilesApi files;
    private final FetchApi fetch;
    private final automately.core.runtime.api.SocketApi sockets;
    private final HttpApi http;
    private final SecretsApi secrets;
    private final JvmCrypto.Pool cryptoPool;

    /** The node's shared stores and clients; one per node. */
    public record Node(Vertx vertx, KvStore kv, FetchClient fetch, ServeRegistry serve, FileStore files, StorageCapacity capacity,
                       TenantQuota quota, Room room, automately.core.queue.QueueService queues, JobsWiring jobs, TenantSecrets secrets,
                       automately.core.files.FileTree tree, automately.core.files.TmpSettings tmp, automately.core.files.FileLocks locks) {

        /** Without locks. */
        public Node(Vertx vertx, KvStore kv, FetchClient fetch, ServeRegistry serve, FileStore files, StorageCapacity capacity,
                    TenantQuota quota, Room room, automately.core.queue.QueueService queues, JobsWiring jobs, TenantSecrets secrets,
                    automately.core.files.FileTree tree, automately.core.files.TmpSettings tmp) {
            this(vertx, kv, fetch, serve, files, capacity, quota, room, queues, jobs, secrets, tree, tmp, null);
        }

        /** Without the file system's folder operations, and with an in-memory /tmp. */
        public Node(Vertx vertx, KvStore kv, FetchClient fetch, ServeRegistry serve, FileStore files, StorageCapacity capacity,
                    TenantQuota quota, Room room, automately.core.queue.QueueService queues, JobsWiring jobs, TenantSecrets secrets) {
            this(vertx, kv, fetch, serve, files, capacity, quota, room, queues, jobs, secrets, null, automately.core.files.TmpSettings.memoryOnly(), null);
        }

        /** Without secrets. */
        public Node(Vertx vertx, KvStore kv, FetchClient fetch, ServeRegistry serve, FileStore files, StorageCapacity capacity,
                    TenantQuota quota, Room room, automately.core.queue.QueueService queues, JobsWiring jobs) {
            this(vertx, kv, fetch, serve, files, capacity, quota, room, queues, jobs, null);
        }

        /** Without files, queues, jobs, storage limits or quotas: for tests. */
        public Node(Vertx vertx, KvStore kv, FetchClient fetch, ServeRegistry serve) {
            this(vertx, kv, fetch, serve, null, StorageCapacity.UNLIMITED, TenantQuota.unlimited(), Room.fixed(Long.MAX_VALUE), null, null, null);
        }
    }

    public NodeHostApis(Node node, String tenant, String jobId, boolean service) {
        this(node, tenant, jobId, service, automately.core.runtime.Packages.NONE);
    }

    public NodeHostApis(Node node, String tenant, String jobId, boolean service, automately.core.runtime.Packages packages) {
        this(node, tenant, jobId, service, packages, JvmCrypto.Pool.inline());
    }

    private NodeHostApis(Node node, String tenant, String jobId, boolean service, automately.core.runtime.Packages packages,
                         JvmCrypto.Pool cryptoPool) {
        this.cryptoPool = cryptoPool;
        this.scope = new HostScope(node.vertx(), tenant, jobId, service);
        this.kv = new NodeKvApi(scope, node.kv(), node.capacity(), node.quota());
        this.bus = new NodeBusApi(scope);
        this.queue = new NodeQueueApi(scope, node.queues());
        this.jobs = new NodeJobsApi(scope, node.jobs());
        this.files = new NodeFilesApi(scope, node.files(), node.capacity(), node.quota(), node.room());
        this.fetch = new NodeFetchApi(node.fetch(), tenant);
        NodeFetchApi requests = (NodeFetchApi) this.fetch;
        this.sockets = node.fetch() == null ? automately.core.runtime.api.SocketApi.UNAVAILABLE
                : new NodeSocketApi(node.fetch().sockets(), node.fetch().http2(), tenant, requests::take);
        this.http = new NodeHttpApi(scope, node.serve());
        this.secrets = new NodeSecretsApi(scope, node.secrets(), node.jobs());
        this.fs = new NodeFsApi(scope, node.files(), node.tree(), node.capacity(), node.quota(), node.room(),
                node.tmp() == null ? automately.core.files.TmpSettings.memoryOnly() : node.tmp(), node.locks(), packages);
    }

    @Override
    public automately.core.runtime.api.FsApi fs() {
        return fs;
    }

    @Override
    public automately.core.runtime.api.CryptoApi crypto() {
        return new JvmCrypto(cryptoPool);
    }

    /** Builds each execution's APIs over one node's stores. */
    public static HostApisFactory factory(Node node) {
        // The node's one pool for costly crypto (D-101), shared by every execution.
        JvmCrypto.Pool cryptoPool = JvmCrypto.Pool.of(node.vertx().createSharedWorkerExecutor(JvmCrypto.POOL_NAME, JvmCrypto.POOL_THREADS));
        return new HostApisFactory() {
            @Override
            public automately.core.runtime.api.HostApis create(String tenant, String jobId, boolean service) {
                return new NodeHostApis(node, tenant, jobId, service, automately.core.runtime.Packages.NONE, cryptoPool);
            }

            @Override
            public automately.core.runtime.api.HostApis create(String tenant, String jobId, boolean service, automately.core.runtime.Packages packages) {
                return new NodeHostApis(node, tenant, jobId, service, packages, cryptoPool);
            }
        };
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
    public automately.core.runtime.api.SocketApi sockets() {
        return sockets;
    }

    @Override
    public HttpApi http() {
        return http;
    }

    @Override
    public SecretsApi secrets() {
        return secrets;
    }

    @Override
    public Future<Void> close() {
        return scope.close();
    }
}
