package cluster93.core.host;

import cluster93.core.runtime.BodyStream;
import cluster93.core.runtime.FetchedResponse;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A response whose body is still being read when the peer closes the connection. An HTTP/1.0 peer (python's
 * {@code http.server} is the one that surfaced this) writes the whole, Content-Length-delimited body and then
 * closes; a client that has paused the body is a {@code Connection was closed} failure away from losing it (D-180).
 */
class FetchBodyCloseTest {

    private Vertx vertx;
    private FetchClient client;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        client = new FetchClient(vertx, new EgressPolicy(true));
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void anHttp10BodySurvivesThePeerClosingAfterIt() throws Exception {
        byte[] body = new byte[1024 * 1024];
        try (Target target = new Target(body, "HTTP/1.0 200 OK", "", true)) {
            assertThat(pausedFetch(target)).isEqualTo(body.length);
        }
    }

    @Test
    void anHttp11BodyWithConnectionCloseSurvivesThePeerClosing() throws Exception {
        byte[] body = new byte[1024 * 1024];
        try (Target target = new Target(body, "HTTP/1.1 200 OK", "Connection: close\r\n", true)) {
            assertThat(pausedFetch(target)).isEqualTo(body.length);
        }
    }

    @Test
    void anHttp11BodySurvivesThePeerKeepingTheConnectionOpen() throws Exception {
        byte[] body = new byte[1024 * 1024];
        try (Target target = new Target(body, "HTTP/1.1 200 OK", "", false)) {
            assertThat(pausedFetch(target)).isEqualTo(body.length);
        }
    }

    /**
     * The same, but with the host's window: the peer has already written the whole body and closed by the time the
     * consumer starts, and the consumer pauses the source at the high mark. The peer's last chunks reach the client
     * in the same read as the close, and a paused client is a {@code Connection was closed} away from dropping them.
     */
    @Test
    void aPausedBodySurvivesThePeerClosingAfterIt() throws Exception {
        byte[] body = new byte[512 * 1024];
        try (Target target = new Target(body, "HTTP/1.0 200 OK", "", true)) {
            assertThat(windowedFetch(target)).isEqualTo(body.length);
        }
    }

    /**
     * The same for a chunked body, where nothing but Vert.x can say where the body ends: the terminator is inside the
     * connection when the peer closes, so the close has to be released before Vert.x judges it.
     */
    @Test
    void aPausedChunkedBodySurvivesThePeerClosingAfterIt() throws Exception {
        byte[] body = new byte[512 * 1024];
        try (Target target = new Target(body, "HTTP/1.1 200 OK", "Connection: close\r\n", true, true)) {
            assertThat(windowedFetch(target)).isEqualTo(body.length);
        }
    }

    /** Fetches, then leaves the body paused long enough for the peer to write the whole thing and close. */
    private int pausedFetch(Target target) throws Exception {
        FetchedResponse response = fetch(target);
        Thread.sleep(500);
        CompletableFuture<Buffer> done = new CompletableFuture<>();
        Buffer all = Buffer.buffer();
        response.body().handler(all::appendBuffer, () -> done.complete(all), done::completeExceptionally);
        response.body().resume();
        return done.get(10, TimeUnit.SECONDS).length();
    }

    /**
     * The same pause, but with a consumer that keeps up the way the host does: chunks pause the source at
     * {@code HIGH_WATER} outstanding and resume it at {@code LOW_WATER}, and the script consumes them from another
     * thread.
     */
    private int windowedFetch(Target target) throws Exception {
        FetchedResponse response = fetch(target);
        Thread.sleep(500);
        Window window = new Window(response.body());
        window.start();
        return window.await().get(10, TimeUnit.SECONDS).intValue();
    }

    private FetchedResponse fetch(Target target) throws Exception {
        return client.fetch(new JsonObject()
                .put("url", "http://127.0.0.1:" + target.port() + "/big.bin"), () -> { })
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** The host's backpressure window (D-176's {@code HostBridge.StreamState}) over a body stream. */
    private static final class Window {

        private static final int HIGH_WATER = 8;
        private static final int LOW_WATER = 2;
        private static final Object END = new Object();

        private final BodyStream stream;
        private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private final CompletableFuture<Long> done = new CompletableFuture<>();
        private int unconsumed;
        private boolean paused;
        private long total;

        Window(BodyStream stream) {
            this.stream = stream;
            Thread consumer = new Thread(this::consume, "fetch-body-close-consumer");
            consumer.setDaemon(true);
            consumer.start();
        }

        void start() {
            stream.handler(this::chunk, () -> queue.add(END), done::completeExceptionally);
            stream.resume();
        }

        CompletableFuture<Long> await() {
            return done;
        }

        private synchronized void chunk(Buffer chunk) {
            unconsumed++;
            if (!paused && unconsumed >= HIGH_WATER) {
                paused = true;
                stream.pause();
            }
            queue.add(chunk);
        }

        private void consume() {
            try {
                while (true) {
                    Object item = queue.take();
                    if (item == END) {
                        done.complete(total);
                        return;
                    }
                    total += ((Buffer) item).length();
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000); // the script's own pace, ~1 ms a chunk
                    synchronized (this) {
                        unconsumed--;
                        if (paused && unconsumed <= LOW_WATER) {
                            paused = false;
                            stream.resume();
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A target that speaks HTTP/1.x over a raw socket, writes one body, and then closes or stays. */
    private static final class Target implements AutoCloseable {

        private final ServerSocket server;
        private final byte[] body;
        private final String statusLine;
        private final String extraHeaders;
        private final boolean closeAfter;
        private final boolean chunked;

        Target(byte[] body, String statusLine, String extraHeaders, boolean closeAfter) throws IOException {
            this(body, statusLine, extraHeaders, closeAfter, false);
        }

        /** With {@code chunked} the body is written as a chunked stream instead of one Content-Length body. */
        Target(byte[] body, String statusLine, String extraHeaders, boolean closeAfter, boolean chunked)
                throws IOException {
            this.body = body;
            this.statusLine = statusLine;
            this.extraHeaders = extraHeaders;
            this.closeAfter = closeAfter;
            this.chunked = chunked;
            this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread thread = new Thread(this::serve, "fetch-body-close-target");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();
                    do {
                        if (!readRequest(in)) {
                            break;
                        }
                        writeResponse(out);
                        out.flush();
                    } while (!closeAfter);
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void writeResponse(OutputStream out) throws IOException {
            String head = chunked
                    ? statusLine + "\r\nTransfer-Encoding: chunked\r\n" + extraHeaders + "\r\n"
                    : statusLine + "\r\nContent-Length: " + body.length + "\r\n" + extraHeaders + "\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            if (!chunked) {
                out.write(body);
                return;
            }
            for (int at = 0; at < body.length; at += 8192) {
                int length = Math.min(8192, body.length - at);
                out.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(body, at, length);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            }
            out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }

        private static boolean readRequest(InputStream in) throws IOException {
            int state = 0;
            int read;
            while ((read = in.read()) >= 0) {
                state = state == 0 && read == '\r' ? 1
                        : state == 1 && read == '\n' ? 2
                        : state == 2 && read == '\r' ? 3
                        : state == 3 && read == '\n' ? 4 : 0;
                if (state == 4) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
