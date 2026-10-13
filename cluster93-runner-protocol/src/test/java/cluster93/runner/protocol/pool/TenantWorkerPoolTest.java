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
    @DisplayName("WorkerProcessHandle transitions state UNBOUND -> BOUND -> BUSY and enforces invariants")
    void testWorkerProcessHandleStateTransitions() {
        FakeProcess fakeProcess = new FakeProcess(9999L);
        FakeStream fakeStream = new FakeStream();

        NodeWorkerChannel channel = new NodeWorkerChannel("worker-9999", fakeStream, fakeStream);
        WorkerProcessHandle handle = new WorkerProcessHandle(fakeProcess, channel);

        assertEquals(WorkerProcessHandle.State.UNBOUND, handle.state());
        assertEquals(9999L, handle.pid());
        assertEquals("worker-9999", handle.workerId());
        assertTrue(handle.isAlive());

        // Bind to tenant "acme"
        handle.bind("acme", new JsonObject(), new JsonObject());
        assertEquals(WorkerProcessHandle.State.BOUND, handle.state());
        assertEquals("acme", handle.boundTenantId());

        // Binding again must fail with IllegalStateException
        var ex = assertThrows(Exception.class, () -> handle.bind("other", new JsonObject(), new JsonObject()).toCompletionStage().toCompletableFuture().join());
        assertTrue(ex.getCause() instanceof IllegalStateException || ex instanceof IllegalStateException);

        // Terminate
        handle.terminate();
        assertEquals(WorkerProcessHandle.State.TERMINATED, handle.state());
        assertFalse(handle.isAlive());
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
        private boolean alive = true;

        FakeProcess(long pid) {
            this.pid = pid;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
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
