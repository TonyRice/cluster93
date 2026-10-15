package cluster93.runner.protocol.pool;

import cluster93.runner.protocol.server.NodeWorkerChannel;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TenantWorkerPoolTest {

    @Test
    @DisplayName("The node-wide budget is what stops n tenants spawning n × capacity workers (D-156)")
    void theNodeBudgetBoundsEveryTenantTogether() {
        // capacity 8, two tenants, and each tenant's own pool would allow all 8: without a shared budget they
        // could hold 16 workers between them.
        WorkerBudget node = new WorkerBudget(8);

        assertTrue(node.hasRoom());
        for (int i = 0; i < 8; i++) {
            assertTrue(node.tryAcquire(), "worker " + i + " should fit in the node's budget");
        }
        // The node is full: a second tenant's pool is refused, whatever its own maxTotal says.
        assertFalse(node.hasRoom());
        assertFalse(node.tryAcquire());
        assertEquals(8, node.used());
        assertEquals(8, node.limit());

        // A worker finishing gives its share back, and only then is there room again.
        node.release();
        assertEquals(7, node.used());
        assertTrue(node.tryAcquire());
        assertEquals(8, node.used());
    }

    @Test
    @DisplayName("An unbounded budget never refuses, for a pool that is the only one")
    void anUnboundedBudgetNeverRefuses() {
        WorkerBudget node = WorkerBudget.unbounded();

        assertFalse(node.bounded());
        for (int i = 0; i < 1000; i++) {
            assertTrue(node.tryAcquire());
        }
        // release() is a no-op when unbounded, so a mismatched call cannot drive the count negative.
        node.release();
        assertEquals(0, node.used());
    }

    @Test
    @DisplayName("A freed worker does not go straight back to the tenant that just had one (D-153)")
    void aTenantWithABacklogDoesNotKeepThePool() {
        TenantRotation rotation = new TenantRotation();
        // One tenant queues a burst; another queues a single request behind it.
        for (int i = 0; i < 10; i++) {
            rotation.enqueue("acme");
        }
        rotation.enqueue("globex");

        // acme is served first (it arrived first) and keeps waiting, because it has a backlog.
        assertEquals("acme", rotation.next());
        rotation.served("acme", true);

        // The next freed worker goes to globex, not to acme's ninth queued request: this is the starvation fix.
        assertEquals("globex", rotation.next());
        rotation.served("globex", false);

        // With globex satisfied, acme has the pool to itself again.
        assertEquals("acme", rotation.next());
    }

    @Test
    @DisplayName("A tenant waiting alone still gets every worker, so a quiet cluster is not throttled")
    void aLoneTenantIsNotThrottled() {
        TenantRotation rotation = new TenantRotation();
        rotation.enqueue("acme");

        assertEquals("acme", rotation.next());
        rotation.served("acme", true);
        // The only waiter, and the one just served: it still gets the next one.
        assertEquals("acme", rotation.next());
        assertEquals(1, rotation.waiting());
    }

    @Test
    @DisplayName("The rotation holds each waiting tenant once, however many requests it queued")
    void theRotationCountsTenantsNotRequests() {
        TenantRotation rotation = new TenantRotation();
        for (int i = 0; i < 5; i++) {
            rotation.enqueue("acme");
        }
        rotation.enqueue("globex");
        rotation.enqueue("initech");

        assertEquals(3, rotation.waiting());
        // Three tenants, so three turns; each arrives in order and stays only while it has work.
        assertEquals("acme", rotation.next());
        rotation.served("acme", false);
        assertEquals("globex", rotation.next());
        rotation.served("globex", false);
        assertEquals("initech", rotation.next());
        rotation.served("initech", false);
        assertNull(rotation.next());
    }

    @Test
    @DisplayName("CgroupMemoryLimiter handles non-existent or restricted paths gracefully")
    void testCgroupMemoryLimiterFallback() throws IOException {
        Path tempDir = Files.createTempDirectory("cgroup-test");
        try {
            CgroupMemoryLimiter limiter = new CgroupMemoryLimiter(tempDir);
            if (limiter.isAvailable()) {
                boolean applied = limiter.applyMemoryLimit(12345, 100_000_000L, 80_000_000L);
                assertTrue(applied);
                assertFalse(limiter.checkOomKilled(12345));
                limiter.cleanup(12345);
            } else {
                assertFalse(limiter.applyMemoryLimit(12345, 100_000_000L, 80_000_000L));
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("WorkerProcessHandle transitions UNBOUND -> BOUND and enforces its invariants")
    void testWorkerProcessHandleStateTransitions() {
        FakeProcess fakeProcess = new FakeProcess(9999L);
        FakeStream fakeStream = new FakeStream();

        NodeWorkerChannel channel = new NodeWorkerChannel("worker-9999", fakeStream, fakeStream);
        WorkerProcessHandle handle = new WorkerProcessHandle(fakeProcess, channel);

        assertEquals(WorkerProcessHandle.State.UNBOUND, handle.state());
        assertEquals(9999L, handle.pid());
        assertEquals("worker-9999", handle.workerId());
        assertTrue(handle.isAlive());
        // A worker that has not been bound cannot take a job, and has no capacity to offer.
        assertFalse(handle.hasCapacity());

        // Bind to tenant "acme"
        handle.bind("acme", new JsonObject(), new JsonObject());
        assertEquals(WorkerProcessHandle.State.BOUND, handle.state());
        assertEquals("acme", handle.boundTenantId());
        assertTrue(handle.hasCapacity());

        // Binding again must fail with IllegalStateException
        var ex = assertThrows(Exception.class, () -> handle.bind("other", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().join());
        assertTrue(ex.getCause() instanceof IllegalStateException || ex instanceof IllegalStateException);

        // Terminate
        handle.terminate();
        assertEquals(WorkerProcessHandle.State.TERMINATED, handle.state());
        assertFalse(handle.isAlive());
    }

    @Test
    @DisplayName("A worker admits several jobs at once, and refuses the one past its limit (D-155)")
    void aWorkerAdmitsSeveralJobsAtOnce() {
        FakeProcess fakeProcess = new FakeProcess(4242L);
        FakeStream fakeStream = new FakeStream();
        NodeWorkerChannel channel = new NodeWorkerChannel("worker-4242", fakeStream, fakeStream);
        // Four jobs at once, as a worker per tenant would run (D-155).
        WorkerProcessHandle handle = new WorkerProcessHandle(fakeProcess, channel, 4);
        handle.bind("acme", new JsonObject(), new JsonObject());

        assertEquals(4, handle.maxJobs());
        assertEquals(0, handle.runningJobs());

        // It is a count, not a flag: the first job no longer makes the worker unusable.
        assertTrue(handle.admitJob());
        assertEquals(1, handle.runningJobs());
        assertTrue(handle.hasCapacity(), "a worker with 1 of 4 jobs still has room");
        assertTrue(handle.admitJob());
        assertTrue(handle.admitJob());
        assertTrue(handle.admitJob());
        assertEquals(4, handle.runningJobs());

        // The fifth is refused rather than overrunning the worker's heap and contexts.
        assertFalse(handle.hasCapacity());
        assertFalse(handle.admitJob());
        assertEquals(4, handle.runningJobs(), "a refused job must not be counted");

        // A job finishing frees exactly one slot, and the worker takes work again.
        handle.jobFinished();
        assertEquals(3, handle.runningJobs());
        assertTrue(handle.admitJob());
        assertEquals(4, handle.runningJobs());
    }

    @Test
    @DisplayName("A worker that dies before its hello fails the acquire instead of hanging it")
    void workerThatDiesBeforeItsHelloFailsAcquire(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("dead-worker.sh"), "#!/bin/sh\nexit 0\n");
        script.toFile().setExecutable(true);
        assertAcquireFails(script, 800);
    }

    @Test
    @DisplayName("A worker that never says hello fails the acquire on the hello timeout")
    void workerThatNeverSaysHelloFailsOnTimeout(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("silent-worker.sh"), "#!/bin/sh\nsleep 60\n");
        script.toFile().setExecutable(true);
        assertAcquireFails(script, 800);
    }

    /** Acquires from a pool whose worker binary is {@code script}; the acquire must fail, not block forever. */
    private static void assertAcquireFails(Path script, long helloTimeoutMs) throws Exception {
        String previous = System.getProperty("cluster93.worker.helloTimeoutMs");
        System.setProperty("cluster93.worker.helloTimeoutMs", String.valueOf(helloTimeoutMs));
        io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
        TenantWorkerPool pool = new TenantWorkerPool(vertx, script.toString(), "javet", 0, 2, 64,
                TenantWorkerPool.ReusePolicy.FRESH_PER_JOB, new CgroupMemoryLimiter(),
                cluster93.runner.protocol.server.NodeRpcAuthorizer.ALLOW_ALL);
        try {
            var acquire = pool.acquire("tenant-a").toCompletionStage().toCompletableFuture();
            java.util.concurrent.ExecutionException failure = assertThrows(
                    java.util.concurrent.ExecutionException.class, () -> acquire.get(15, java.util.concurrent.TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            // The failed spawn must release its slot too, or the pool's maxTotal budget drifts negative (D-135).
            assertEquals(0, pool.totalWorkers());
        } finally {
            if (previous == null) {
                System.clearProperty("cluster93.worker.helloTimeoutMs");
            } else {
                System.setProperty("cluster93.worker.helloTimeoutMs", previous);
            }
            pool.close();
            vertx.close().toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("One job ending on a shared worker keeps it alive for its sibling (D-155)")
    void aJobEndingDoesNotKillAWorkerThatStillHasJobs() {
        io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
        TenantWorkerPool pool = newPool(vertx, TenantWorkerPool.ReusePolicy.FRESH_PER_JOB);
        try {
            FakeProcess process = new FakeProcess(7001L);
            WorkerProcessHandle handle = boundHandle(process, 7001L, "acme", 4);

            // Two jobs run on the one worker.
            pool.jobStarted(handle, "acme");
            pool.jobStarted(handle, "acme");
            assertEquals(2, pool.jobsOn(handle));

            // One job ends — a cancelled child, a failed run: a run-level event, so the worker and its sibling stay.
            pool.release(handle);
            assertEquals(1, pool.jobsOn(handle));
            assertTrue(process.isAlive(), "a job ending must not reap a worker that still has a sibling on it");
            assertTrue(handle.isAlive());

            // The last job ends: the worker is now idle, and fresh-per-job reaps it.
            pool.release(handle);
            assertEquals(0, pool.jobsOn(handle));
            assertFalse(process.isAlive());
        } finally {
            closeQuietly(vertx, pool);
        }
    }

    @Test
    @DisplayName("A worker killed while its sibling runs is reaped even though the sibling is still there (D-155)")
    void poisoningAWorkerReapsItEvenWithLiveJobs() {
        io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
        TenantWorkerPool pool = newPool(vertx, TenantWorkerPool.ReusePolicy.FRESH_PER_JOB);
        try {
            FakeProcess process = new FakeProcess(7002L);
            WorkerProcessHandle handle = boundHandle(process, 7002L, "acme", 4);
            pool.jobStarted(handle, "acme");
            pool.jobStarted(handle, "acme");

            // The worker itself is bad — a broken channel, an OOM. It goes even with a job still on it.
            pool.poison(handle, "channel broke");
            assertEquals(0, pool.jobsOn(handle));
            assertFalse(process.isAlive());
        } finally {
            closeQuietly(vertx, pool);
        }
    }

    @Test
    @DisplayName("A release that finds the worker gone reaps it, so its slot is not leaked")
    void releaseOnADeadWorkerReapsIt() {
        io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
        TenantWorkerPool pool = newPool(vertx, TenantWorkerPool.ReusePolicy.FRESH_PER_JOB);
        try {
            FakeProcess process = new FakeProcess(7003L);
            WorkerProcessHandle handle = boundHandle(process, 7003L, "acme", 4);
            pool.jobStarted(handle, "acme");

            process.destroy(); // the process died on its own while the job ran
            pool.release(handle);
            assertEquals(0, pool.jobsOn(handle), "a dead worker must not keep a job counted");
            assertFalse(handle.isAlive());
        } finally {
            closeQuietly(vertx, pool);
        }
    }

    @Test
    @DisplayName("A stray second release is a no-op, not a negative job count")
    void aStraySecondReleaseIsHarmless() {
        io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
        TenantWorkerPool pool = newPool(vertx, TenantWorkerPool.ReusePolicy.FRESH_PER_JOB);
        try {
            FakeProcess process = new FakeProcess(7004L);
            WorkerProcessHandle handle = boundHandle(process, 7004L, "acme", 4);
            pool.jobStarted(handle, "acme");

            pool.release(handle); // the real completion
            pool.release(handle); // its cancel path released too: must not double-count or throw
            assertEquals(0, pool.jobsOn(handle));
        } finally {
            closeQuietly(vertx, pool);
        }
    }

    @Test
    @DisplayName("With reuse on, an idle worker is kept warm rather than reaped (D-155)")
    void reuseKeepsTheWorkerWarm() {
        io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
        TenantWorkerPool pool = newPool(vertx, TenantWorkerPool.ReusePolicy.REUSE_SAME_TENANT);
        try {
            FakeProcess process = new FakeProcess(7005L);
            WorkerProcessHandle handle = boundHandle(process, 7005L, "acme", 4);
            pool.jobStarted(handle, "acme");

            pool.release(handle);
            assertEquals(0, pool.jobsOn(handle));
            assertTrue(process.isAlive(), "a healthy idle worker is kept for its tenant under a reuse policy");
        } finally {
            closeQuietly(vertx, pool);
        }
    }

    private static TenantWorkerPool newPool(io.vertx.core.Vertx vertx, TenantWorkerPool.ReusePolicy policy) {
        // minWarm 0 so nothing is spawned: these tests drive the lifecycle bookkeeping directly.
        return new TenantWorkerPool(vertx, null, "javet", 0, 4, 64, policy, new CgroupMemoryLimiter(),
                cluster93.runner.protocol.server.NodeRpcAuthorizer.ALLOW_ALL);
    }

    private static WorkerProcessHandle boundHandle(FakeProcess process, long pid, String tenant, int maxJobs) {
        NodeWorkerChannel channel = new NodeWorkerChannel("worker-" + pid, new FakeStream(), new FakeStream());
        WorkerProcessHandle handle = new WorkerProcessHandle(process, channel, maxJobs);
        handle.bind(tenant, new JsonObject(), new JsonObject());
        return handle;
    }

    private static void closeQuietly(io.vertx.core.Vertx vertx, TenantWorkerPool pool) {
        try {
            pool.close();
            vertx.close().toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }

    static class FakeProcess extends Process {
        private final long pid;
        private volatile int exitCode;
        private volatile boolean alive = true;

        FakeProcess(long pid) {
            this(pid, 0);
        }

        FakeProcess(long pid, int exitCode) {
            this.pid = pid;
            this.exitCode = exitCode;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return exitCode; }
        /** Like a real process: there is no exit code until it has exited. */
        @Override public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("process has not exited");
            }
            return exitCode;
        }
        @Override public void destroy() { alive = false; }
        @Override public boolean isAlive() { return alive; }
        @Override public long pid() { return pid; }
    }

    static class FakeStream implements ReadStream<Buffer>, WriteStream<Buffer> {
        @Override public Future<Void> write(Buffer data) { return Future.succeededFuture(); }
        @Override public Future<Void> end() { return Future.succeededFuture(); }
        @Override public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) { return this; }
        @Override public boolean writeQueueFull() { return false; }
        @Override public WriteStream<Buffer> drainHandler(Handler<Void> handler) { return this; }
        @Override public FakeStream exceptionHandler(Handler<Throwable> handler) { return this; }
        @Override public FakeStream handler(Handler<Buffer> handler) { return this; }
        @Override public FakeStream pause() { return this; }
        @Override public FakeStream resume() { return this; }
        @Override public FakeStream fetch(long amount) { return this; }
        @Override public FakeStream endHandler(Handler<Void> handler) { return this; }
    }
}
