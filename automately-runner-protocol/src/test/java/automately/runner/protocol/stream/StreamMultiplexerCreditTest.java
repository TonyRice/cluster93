package automately.runner.protocol.stream;

import automately.runner.protocol.frame.Frame;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stream longer than the 4 MiB connection window must not deadlock: the receiver has to replenish both the stream's
 * credit and the shared connection credit (D-128, worker-node-protocol.md §5).
 */
class StreamMultiplexerCreditTest {

    @Test
    @DisplayName("A stream over 8 MiB completes: connection credit is replenished alongside stream credit")
    void streamsMoreThanTheConnectionWindow() throws Exception {
        int totalBytes = 10 * 1024 * 1024;            // well over the 4 MiB connection window
        int chunkBytes = 64 * 1024;

        // Frame delivery runs on pump threads, so credit frames travel back without unbounded recursion.
        LinkedBlockingQueue<Runnable> toServer = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<Runnable> toClient = new LinkedBlockingQueue<>();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<StreamMultiplexer> clientRef = new AtomicReference<>();
        AtomicReference<StreamMultiplexer> serverRef = new AtomicReference<>();

        StreamMultiplexer client = new StreamMultiplexer(frame -> {
            enqueue(toServer, () -> serverRef.get().handleFrame(frame));
            return Future.succeededFuture();
        });
        StreamMultiplexer server = new StreamMultiplexer(frame -> {
            enqueue(toClient, () -> clientRef.get().handleFrame(frame));
            return Future.succeededFuture();
        });
        clientRef.set(client);
        serverRef.set(server);

        Thread serverPump = pump("credit-server-pump", toServer, running);
        Thread clientPump = pump("credit-client-pump", toClient, running);

        int streamId = 13;
        AtomicLong received = new AtomicLong();
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        CountDownLatch ended = new CountDownLatch(1);
        ReadStream<Buffer> in = server.createInputStream(streamId);
        in.handler(buf -> {
            received.addAndGet(buf.length());
            digest.update(buf.getBytes());
        });
        in.endHandler(v -> ended.countDown());

        WriteStream<Buffer> out = client.createOutputStream(streamId);
        java.security.MessageDigest expected;
        try {
            expected = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        byte[] payload = new byte[chunkBytes];
        List<Future<Void>> writes = new ArrayList<>();
        for (int sent = 0; sent < totalBytes; sent += chunkBytes) {
            for (int i = 0; i < chunkBytes; i++) {
                payload[i] = (byte) ((sent + i) * 31 + 7);
            }
            expected.update(payload);
            writes.add(out.write(Buffer.buffer(payload)));
        }
        out.end();

        assertTrue(ended.await(60, TimeUnit.SECONDS), "the stream should end without deadlocking");
        assertEquals(totalBytes, received.get());
        assertEquals(java.util.HexFormat.of().formatHex(expected.digest()), java.util.HexFormat.of().formatHex(digest.digest()),
                "the received bytes must match what was sent");

        running.set(false);
        serverPump.join(5_000);
        clientPump.join(5_000);
    }

    private static void enqueue(LinkedBlockingQueue<Runnable> queue, Runnable task) {
        queue.add(task);
    }

    private static Thread pump(String name, LinkedBlockingQueue<Runnable> queue, AtomicBoolean running) {
        Thread thread = new Thread(() -> {
            while (running.get()) {
                try {
                    Runnable task = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        task.run();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
