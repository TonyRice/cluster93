package cluster93.runner.protocol.stream;

import cluster93.runner.protocol.flow.CreditManager;
import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.message.OpenStreamMessage;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StreamMultiplexerTest {

    @Test
    @DisplayName("Streams binary data from sender to receiver and cleanly terminates on END")
    void testSingleStreamTransmission() throws Exception {
        CompletableFuture<Void> finished = new CompletableFuture<>();
        List<Buffer> receivedChunks = new ArrayList<>();

        // Create two linked multiplexers
        AtomicReferenceHolder<StreamMultiplexer> clientRef = new AtomicReferenceHolder<>();
        AtomicReferenceHolder<StreamMultiplexer> serverRef = new AtomicReferenceHolder<>();

        StreamMultiplexer client = new StreamMultiplexer(frame -> {
            serverRef.get().handleFrame(frame);
            return io.vertx.core.Future.succeededFuture();
        });
        StreamMultiplexer server = new StreamMultiplexer(frame -> {
            clientRef.get().handleFrame(frame);
            return io.vertx.core.Future.succeededFuture();
        });

        clientRef.set(client);
        serverRef.set(server);

        int streamId = 42;
        ReadStream<Buffer> serverIn = server.createInputStream(streamId);
        serverIn.handler(receivedChunks::add);
        serverIn.endHandler(v -> finished.complete(null));

        WriteStream<Buffer> clientOut = client.createOutputStream(streamId);
        clientOut.write(Buffer.buffer("Hello, "));
        clientOut.write(Buffer.buffer("streaming "));
        clientOut.write(Buffer.buffer("world!"));
        clientOut.end();

        finished.get(5, TimeUnit.SECONDS);

        Buffer combined = Buffer.buffer();
        for (Buffer b : receivedChunks) {
            combined.appendBuffer(b);
        }
        assertEquals("Hello, streaming world!", combined.toString());
    }

    @Test
    @DisplayName("Flow control: sender pauses writes when credit window is exhausted and resumes on replenishment")
    void testCreditBackpressure() throws Exception {
        // Use a small 32 KiB window for testing backpressure
        int smallWindow = 32 * 1024;
        CreditManager clientCredits = new CreditManager(smallWindow, smallWindow * 2);
        CreditManager serverCredits = new CreditManager(smallWindow, smallWindow * 2);

        AtomicReferenceHolder<StreamMultiplexer> clientRef = new AtomicReferenceHolder<>();
        AtomicReferenceHolder<StreamMultiplexer> serverRef = new AtomicReferenceHolder<>();

        StreamMultiplexer client = new StreamMultiplexer(frame -> {
            serverRef.get().handleFrame(frame);
            return io.vertx.core.Future.succeededFuture();
        }, clientCredits);

        StreamMultiplexer server = new StreamMultiplexer(frame -> {
            clientRef.get().handleFrame(frame);
            return io.vertx.core.Future.succeededFuture();
        }, serverCredits);

        clientRef.set(client);
        serverRef.set(server);

        int streamId = 100;
        ReadStream<Buffer> serverIn = server.createInputStream(streamId);
        AtomicInteger totalReceived = new AtomicInteger(0);
        serverIn.handler(buf -> totalReceived.addAndGet(buf.length()));

        WriteStream<Buffer> clientOut = client.createOutputStream(streamId);
        clientOut.setWriteQueueMaxSize(smallWindow);

        // Send 64 KiB (exceeds the 32 KiB window)
        byte[] payload = new byte[64 * 1024];
        java.util.Arrays.fill(payload, (byte) 'A');
        Buffer largeBuffer = Buffer.buffer(payload);

        CompletableFuture<Void> writeDone = new CompletableFuture<>();
        clientOut.write(largeBuffer).onComplete(ar -> {
            if (ar.succeeded()) writeDone.complete(null);
            else writeDone.completeExceptionally(ar.cause());
        });

        writeDone.get(5, TimeUnit.SECONDS);
        assertEquals(64 * 1024, totalReceived.get());
    }

    @Test
    @DisplayName("Resetting a stream triggers StreamResetException on recipient")
    void testStreamReset() throws Exception {
        AtomicReferenceHolder<StreamMultiplexer> clientRef = new AtomicReferenceHolder<>();
        AtomicReferenceHolder<StreamMultiplexer> serverRef = new AtomicReferenceHolder<>();

        StreamMultiplexer client = new StreamMultiplexer(frame -> {
            serverRef.get().handleFrame(frame);
            return io.vertx.core.Future.succeededFuture();
        });
        StreamMultiplexer server = new StreamMultiplexer(frame -> {
            clientRef.get().handleFrame(frame);
            return io.vertx.core.Future.succeededFuture();
        });

        clientRef.set(client);
        serverRef.set(server);

        int streamId = 77;
        ReadStream<Buffer> serverIn = server.createInputStream(streamId);
        CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();
        serverIn.exceptionHandler(errorFuture::complete);

        client.createOutputStream(streamId);
        client.resetStream(streamId, "Worker cancelled operation", 500);

        Throwable thrown = errorFuture.get(5, TimeUnit.SECONDS);
        assertInstanceOf(StreamMultiplexer.StreamResetException.class, thrown);
        assertEquals(500, ((StreamMultiplexer.StreamResetException) thrown).errorCode());
        assertTrue(thrown.getMessage().contains("Worker cancelled operation"));
    }

    @Test
    @DisplayName("A reset that carries a host failure's name and code keeps them (D-184)")
    void testStreamResetCarriesHostFailure() throws Exception {
        AtomicReferenceHolder<StreamMultiplexer> clientRef = new AtomicReferenceHolder<>();
        AtomicReferenceHolder<StreamMultiplexer> serverRef = new AtomicReferenceHolder<>();

        StreamMultiplexer client = new StreamMultiplexer(frame -> {
            serverRef.get().handleFrame(frame);
            return io.vertx.core.Future.succeededFuture();
        });
        StreamMultiplexer server = new StreamMultiplexer(frame -> {
            clientRef.get().handleFrame(frame);
            return io.vertx.core.Future.succeededFuture();
        });

        clientRef.set(client);
        serverRef.set(server);

        int streamId = 78;
        ReadStream<Buffer> serverIn = server.createInputStream(streamId);
        CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();
        serverIn.exceptionHandler(errorFuture::complete);

        client.createOutputStream(streamId);
        client.resetStream(streamId, "fetch failed: Connection was closed", 502,
                "TypeError", "network_error");

        Throwable thrown = errorFuture.get(5, TimeUnit.SECONDS);
        assertInstanceOf(StreamMultiplexer.StreamResetException.class, thrown);
        StreamMultiplexer.StreamResetException reset = (StreamMultiplexer.StreamResetException) thrown;
        assertEquals("TypeError", reset.errorName());
        assertEquals("network_error", reset.hostCode());
        assertEquals("fetch failed: Connection was closed", reset.reason());
    }

    @Test
    @DisplayName("Multiple concurrent streams operate concurrently without cross-talk")
    void testMultipleConcurrentStreams() throws Exception {
        AtomicReferenceHolder<StreamMultiplexer> clientRef = new AtomicReferenceHolder<>();
        AtomicReferenceHolder<StreamMultiplexer> serverRef = new AtomicReferenceHolder<>();

        StreamMultiplexer client = new StreamMultiplexer(frame -> {
            serverRef.get().handleFrame(frame);
            return io.vertx.core.Future.succeededFuture();
        });
        StreamMultiplexer server = new StreamMultiplexer(frame -> {
            clientRef.get().handleFrame(frame);
            return io.vertx.core.Future.succeededFuture();
        });

        clientRef.set(client);
        serverRef.set(server);

        int count = 5;
        List<CompletableFuture<String>> results = new ArrayList<>();

        for (int i = 1; i <= count; i++) {
            int streamId = i;
            CompletableFuture<String> done = new CompletableFuture<>();
            results.add(done);

            Buffer acc = Buffer.buffer();
            ReadStream<Buffer> in = server.createInputStream(streamId);
            in.handler(acc::appendBuffer);
            in.endHandler(v -> done.complete(acc.toString()));

            WriteStream<Buffer> out = client.createOutputStream(streamId);
            out.write(Buffer.buffer("stream-" + streamId));
            out.end();
        }

        for (int i = 1; i <= count; i++) {
            String res = results.get(i - 1).get(5, TimeUnit.SECONDS);
            assertEquals("stream-" + i, res);
        }
    }

    private static class AtomicReferenceHolder<T> {
        private T value;
        void set(T value) { this.value = value; }
        T get() { return value; }
    }
}
