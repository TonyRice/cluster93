package cluster93.worker;

import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker is a separate process with its own classpath (D-126, D-127): it speaks the runner protocol over a
 * socket, and it must never see the cluster manager. That isolation is what keeps "the worker cannot reach Ignite"
 * structural rather than accidental — and it would break quietly, because nothing in the worker's own code would
 * notice an Ignite jar arriving transitively through {@code core} or {@code runner-protocol}.
 *
 * <p>This asserts the property at the classpath, which is the layer the mistake actually happens: someone adds a
 * dependency to {@code core} or {@code runner-protocol}, and one build later the worker image carries Apache Ignite.
 * The positive checks keep the negative ones honest — they prove this is looking at the real worker classpath and
 * not at an empty one — and mirror the chain the worker is allowed to have,
 * {@code worker → runner-protocol → core → vertx-core}, plus {@code runtime-js}.
 *
 * <p>Companion to {@code cluster93.server.ArchitectureTest.igniteStaysInsideItsModule} (D-030): that rule sees every
 * class on the <em>server</em> classpath, which includes Ignite but not {@code cluster93.worker}; this one sees the
 * worker's own, which includes neither Ignite nor the cluster manager.
 */
class WorkerClasspathIsolationTest {

    /** The loader that resolves the worker's runtime classpath, not a fresh one. */
    private static final ClassLoader WORKER_CLASSPATH = WorkerClasspathIsolationTest.class.getClassLoader();

    /**
     * Positive control: the stack the worker is built on is genuinely on this classpath. If any of these were absent,
     * {@link #noClusterManagerReachesTheWorker()} would be passing for the wrong reason.
     */
    @Test
    void theWorkerSeesTheProtocolStackItDependsOn() {
        assertThat(onClasspath("cluster93/worker/WorkerMain.class"))
                .as("the worker's own classes").isNotNull();
        assertThat(onClasspath("cluster93/runner/protocol/frame/Frame.class"))
                .as("cluster93-runner-protocol").isNotNull();
        assertThat(onClasspath("cluster93/core/scheduler/NodeVerticle.class"))
                .as("cluster93-core").isNotNull();
        assertThat(onClasspath("cluster93/runtime/js/HostFunction.class"))
                .as("cluster93-runtime-js").isNotNull();
        assertThat(onClasspath("io/vertx/core/Vertx.class"))
                .as("vertx-core").isNotNull();
    }

    /** No path reaches the worker (D-030): the cluster manager and its caches stay on the node, never in a worker. */
    @Test
    void noClusterManagerReachesTheWorker() {
        assertThat(onClasspath("org/apache/ignite/Ignite.class"))
                .as("Apache Ignite must not be on the worker classpath (D-030)").isNull();
        assertThat(onClasspath("io/vertx/spi/cluster/ignite/IgniteClusterManager.class"))
                .as("the Vert.x Ignite cluster manager must not be on the worker classpath").isNull();
        assertThat(onClasspath("cluster93/cluster/ignite/IgniteCluster.class"))
                .as("cluster93-cluster-ignite must not be on the worker classpath").isNull();
        assertThat(onClasspath("com/hazelcast/core/HazelcastInstance.class"))
                .as("Hazelcast must not be on the worker classpath").isNull();
    }

    private static URL onClasspath(String resource) {
        return WORKER_CLASSPATH.getResource(resource);
    }
}
