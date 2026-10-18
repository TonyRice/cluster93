package cluster93.runner.protocol.client;

import cluster93.core.job.OrphanPolicy;
import cluster93.core.runtime.Execution;
import cluster93.core.runtime.ExecutionResult;
import cluster93.core.runtime.JobContext;
import cluster93.core.runtime.LogSink;
import cluster93.core.runtime.Packages;
import cluster93.core.runtime.RunningExecution;
import cluster93.core.runtime.ScriptRuntime;
import cluster93.core.runtime.StateSink;
import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.frame.FrameType;
import cluster93.runner.protocol.message.BindMessage;
import cluster93.runner.protocol.message.PolicyMessage;
import cluster93.runner.protocol.message.EventMessage;
import cluster93.runner.protocol.message.HelloMessage;
import cluster93.runner.protocol.message.LogMessage;
import cluster93.runner.protocol.message.OpenStreamMessage;
import cluster93.runner.protocol.message.ResultRunMessage;
import cluster93.runner.protocol.message.RunMessage;
import cluster93.runner.protocol.message.StateMessage;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Worker-side protocol daemon: handles HELLO, BIND, and RUN dispatching to a {@link ScriptRuntime}, and routes the
 * node's asynchronous events and response streams to the run's {@link RemoteHostApis}
 * (worker-node-protocol.md §4, §6, D-128).
 */
public class WorkerDaemon {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerDaemon.class);

    private final WorkerClientConnection connection;
    private final ScriptRuntime runtime;
    private final String engineName;
    /** The pre-shared secret to present in HELLO (D-147, D-168); null when the node spawned this worker over a pipe. */
    private final String secret;
    /** This worker's per-job heap budget and how many jobs it runs at once, so the node can reserve on connect (D-169). */
    private final int heapMb;
    private final int maxJobs;
    private String boundTenantId;
    /**
     * The egress policy this worker decides with when the node's placement is {@code worker} (D-173): what the node
     * synced, and nothing else. It refuses everything until the first policy arrives, and again once one expires.
     */
    private final SyncedPolicySource egressPolicy = new SyncedPolicySource();
    /** Where a local gate would run: null until {@link #egressLocally}, which a real worker always calls (D-173/A4). */
    private volatile io.vertx.core.Vertx vertx;
    private final WorkerAccounting accounting = new WorkerAccounting();
    private volatile cluster93.core.host.egress.Egress gate;
    /**
     * The network client this worker makes its own connections with (D-176): one per worker, not one per run. The
     * cluster's per-node limits are enforced through the client's own counters and rate bucket, so a client per run
     * would hand every run of the tenant a fresh allowance — the node divides its allowance by the worker, so the
     * worker has to be the thing that holds it.
     */
    private volatile cluster93.core.host.FetchClient localFetch;
    /** How often a worker tells the node what it refused on its own (D-176), unless a test says otherwise. */
    static final long REPORT_INTERVAL_MS = 5_000L;
    private long reportTimer = -1L;

    private final Map<Integer, RunningExecution> activeRuns = new ConcurrentHashMap<>();
    private final Map<Integer, RemoteHostApis> hostsByChannel = new ConcurrentHashMap<>();
    private final Map<String, RemoteHostApis> hostsByRunId = new ConcurrentHashMap<>();

    public WorkerDaemon(WorkerClientConnection connection, ScriptRuntime runtime, String engineName) {
        this(connection, runtime, engineName, null, 0, 0);
    }

    /**
     * @param secret  the pre-shared secret to present in {@code HELLO} (D-168), or null/blank for a worker the node
     *                spawned over a pipe — a pipe already binds the channel to the child the node made
     * @param heapMb  this worker's per-job heap budget, as its engine was started
     * @param maxJobs how many jobs it runs at once (its {@code --threads}); the deployment sized it, and the node
     *                reserves memory for it from connect, so it declares both (D-169)
     */
    public WorkerDaemon(WorkerClientConnection connection, ScriptRuntime runtime, String engineName,
                        String secret, int heapMb, int maxJobs) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.engineName = Objects.requireNonNull(engineName, "engineName");
        this.secret = secret;
        this.heapMb = heapMb;
        this.maxJobs = maxJobs;

        connection.setFrameHandler(this::onFrame);
        connection.streamMultiplexer().setOpenHandler(this::onOpenStream);
    }

    /**
     * Starts the worker by sending the HELLO announcement to the node.
     */
    public Future<Void> start() {
        long pid = ProcessHandle.current().pid();
        // It can hold and apply the egress rules the node sends (D-173): say so, so the node hands it any.
        HelloMessage hello = new HelloMessage(engineName, "2.0.0", pid, secret, heapMb, maxJobs, true);
        return connection.send(hello.toFrame());
    }

    public String boundTenantId() {
        return boundTenantId;
    }

    private void onFrame(Frame frame) {
        switch (frame.type()) {
            case WELCOME -> LOG.info("Worker welcomed by node");
            case BIND -> onBind(frame);
            case POLICY -> onPolicy(frame);
            case RUN -> onRun(frame);
            case STOP -> onStop(frame);
            case CANCEL -> onCancel(frame);
            case EVENT -> onEvent(frame);
            case GOODBYE -> {
                // The node is going away; tell it what it has not been told yet while there is still a connection (D-176).
                reportRefusals();
                LOG.info("Worker received GOODBYE from node");
            }
            default -> LOG.warn("Worker received unhandled frame {}", frame.type());
        }
    }

    private void onBind(Frame frame) {
        BindMessage bind = BindMessage.fromFrame(frame);
        this.boundTenantId = bind.tenantId();
        LOG.info("Worker successfully bound to tenant {}", boundTenantId);
    }

    /**
     * The node's egress policy for this worker's placement (D-173): versioned, and valid until it expires. An older
     * version changes nothing, and a policy for another tenant is not applied — the decision reads it through
     * {@link #egressPolicy()}.
     */
    private void onPolicy(Frame frame) {
        PolicyMessage policy = PolicyMessage.fromFrame(frame);
        if (!egressPolicy.apply(policy)) {
            LOG.debug("Ignored egress policy v{} (tenant {})", policy.version(), policy.tenantId());
            return;
        }
        LOG.info("Egress policy v{} for tenant {} (placement {}, expires in {} ms): this worker decides for itself",
                policy.version(), policy.tenantId(), policy.placement(), policy.expiresInMs());
    }

    /** The policy this worker's own gate decides under (D-173); empty and refusing until the node sends one. */
    public SyncedPolicySource egressPolicy() {
        return egressPolicy;
    }

    /** What this worker refused, when it was the one deciding (D-173). */
    public WorkerAccounting accounting() {
        return accounting;
    }

    /**
     * Lets this worker decide and make its own connections (D-173/A4), given the Vert.x it runs on. A real worker
     * always calls this; left unset, every host call goes to the node, which is what a worker that cannot hold a policy
     * (an older build, one that never advertised {@code egressPolicy}) does anyway.
     */
    public WorkerDaemon egressLocally(io.vertx.core.Vertx vertx) {
        return egressLocally(vertx, REPORT_INTERVAL_MS);
    }

    /** @param reportIntervalMs how often the worker tells the node what it refused (D-176); short in tests */
    public WorkerDaemon egressLocally(io.vertx.core.Vertx vertx, long reportIntervalMs) {
        this.vertx = vertx;
        long interval = Math.max(1L, reportIntervalMs);
        if (reportTimer == -1L) {
            // Batched rather than one frame per refusal: a script looping on a refused address would otherwise turn
            // its own refusals into protocol traffic, which is the last thing a node at its ceiling needs.
            reportTimer = vertx.setPeriodic(interval, id -> reportRefusals());
        }
        return this;
    }

    /**
     * Tells the node what this worker refused on its own (D-176). The node is not in the path of these connections, so
     * without this its metrics would show only what it refused itself — and a worker that loses its connection loses
     * the report with it, which is why the tally is not cleared by sending it.
     */
    private void reportRefusals() {
        WorkerClientConnection connectionOn = connection;
        String tenant = boundTenantId;
        if (connectionOn == null || tenant == null) {
            return;
        }
        Map<String, Long> denied = accounting.drainDenials();
        if (denied.isEmpty()) {
            return;
        }
        connectionOn.send(new cluster93.runner.protocol.message.AccountMessage(tenant, denied).toFrame())
                .onFailure(err -> LOG.debug("Could not report egress refusals to the node: {}", String.valueOf(err.getMessage())));
    }

    /**
     * The network host APIs this run serves itself, or null when the node should serve them: only when the node says
     * the placement is the worker (D-173 item 8) <em>and</em> the policy in force is fresh — a worker without rules it
     * can trust asks the node rather than guess.
     */
    private RemoteHostApis.LocalNetwork localNetwork(String tenant) {
        io.vertx.core.Vertx vertxOn = this.vertx;
        if (vertxOn == null
                || !cluster93.runner.protocol.message.PolicyMessage.WORKER.equals(egressPolicy.placement())
                || !egressPolicy.fresh()) {
            return null;
        }
        cluster93.core.host.egress.Egress gateOn = gate;
        if (gateOn == null) {
            synchronized (this) {
                if (gate == null) {
                    gate = new cluster93.core.host.egress.Egress(egressPolicy, accounting,
                            new cluster93.core.host.dns.DnsResolver(vertxOn, java.util.List.of()));
                }
                gateOn = gate;
            }
        }
        // One client for the whole worker (D-176): its tallies and its rate bucket are what make the node's allocation
        // real, and they have to span this worker's runs, not restart with each of them.
        cluster93.core.host.FetchClient client = localFetch;
        if (client == null) {
            synchronized (this) {
                if (localFetch == null) {
                    localFetch = new cluster93.core.host.FetchClient(vertxOn, gateOn);
                }
                client = localFetch;
            }
        }
        cluster93.core.host.NodeFetchApi fetchApi = new cluster93.core.host.NodeFetchApi(client, tenant);
        // The socket path too (D-116, D-117): `net`, `tls`, the WebSocket client and `node:http`'s client half ride it,
        // and it shares the script's concurrent-request places with the fetches.
        return new RemoteHostApis.LocalNetwork(fetchApi, new cluster93.core.host.NodeDnsApi(gateOn, tenant),
                new cluster93.core.host.NodeSocketApi(client.sockets(), client.http2(), tenant, fetchApi::take));
    }

    private void onRun(Frame frame) {
        RunMessage runMsg = RunMessage.fromFrame(frame);
        int channelId = runMsg.channelId();
        String runId = runMsg.runId();

        RemoteHostApis hostApis = new RemoteHostApis(connection, runId, channelId, localNetwork(boundTenantId));
        LogSink logs = (level, line) -> connection.send(new LogMessage(channelId, runId, level, line).toFrame());
        StateSink state = new StateSink() {
            @Override
            public void saveCheckpoint(Object value) {
                connection.send(StateMessage.checkpoint(channelId, runId, value).toFrame());
            }

            @Override
            public void completeStep(String name, Object result) {
                connection.send(StateMessage.step(channelId, runId, name, result).toFrame());
            }
        };

        JobContext jobContext = JobContext.fromJson(runMsg.context());
        if (jobContext == null) {
            jobContext = new JobContext(runId, 1, OrphanPolicy.FAIL, null, cluster93.core.state.JobState.EMPTY, null);
        }
        Packages packages = Packages.fromJson(runMsg.packages());
        Map<String, String> env = new java.util.LinkedHashMap<>();
        runMsg.env().forEach(entry -> env.put(entry.getKey(), String.valueOf(entry.getValue())));

        Execution execution = new Execution(runId, boundTenantId != null ? boundTenantId : "unbound",
                runMsg.script(), runMsg.input(), Duration.ofMillis(runMsg.timeoutMs()), jobContext,
                runMsg.longRunning(), packages, env);

        hostsByChannel.put(channelId, hostApis);
        hostsByRunId.put(runId, hostApis);
        RunningExecution running = runtime.execute(execution, logs, state, hostApis);
        activeRuns.put(channelId, running);

        running.result().onComplete(ar -> {
            activeRuns.remove(channelId);
            hostsByChannel.remove(channelId);
            hostsByRunId.remove(runId);
            hostApis.close();

            ResultRunMessage resultMsg;
            if (ar.succeeded()) {
                ExecutionResult res = ar.result();
                resultMsg = new ResultRunMessage(channelId, runId, res.status().name(), res.output(), res.error());
            } else {
                resultMsg = new ResultRunMessage(channelId, runId, "FAILED", null, ar.cause().getMessage());
            }
            connection.send(resultMsg.toFrame());
        });
    }

    private void onEvent(Frame frame) {
        EventMessage event = EventMessage.fromFrame(frame);
        RemoteHostApis host = hostsByChannel.get(event.channelId());
        if (host != null) {
            host.dispatchEvent(event);
        } else {
            LOG.warn("Worker received event {} for unknown run channel {}", event.type(), event.channelId());
        }
    }

    private void onOpenStream(OpenStreamMessage open) {
        String runId = open.metadata().getString("runId");
        RemoteHostApis host = runId != null ? hostsByRunId.get(runId) : null;
        if (host == null) {
            connection.streamMultiplexer().resetStream(open.streamId(), "UNKNOWN_RUN", 404);
            return;
        }
        host.onIncomingStream(open);
    }

    private void onStop(Frame frame) {
        int channelId = frame.channelId();
        RunningExecution running = activeRuns.get(channelId);
        if (running != null) {
            String reason = frame.json().getString("reason", "Requested stop");
            long graceMs = frame.json().getLong("graceMs", 5000L);
            running.stop(reason, Duration.ofMillis(graceMs));
        }
    }

    private void onCancel(Frame frame) {
        int channelId = frame.channelId();
        RunningExecution running = activeRuns.get(channelId);
        if (running != null) {
            String reason = frame.json().getString("reason", "Requested cancel");
            running.cancel(reason);
        }
    }
}
