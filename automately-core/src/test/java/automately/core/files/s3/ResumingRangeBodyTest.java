package automately.core.files.s3;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Every way an S3 range download can be cut off (D-081, D-094), driven by hand so each one happens every time. */
class ResumingRangeBodyTest {

    /** A response body the test feeds: chunks, an end, or an error, whenever it likes. */
    static final class FakeStream implements ReadStream<Buffer> {
        Handler<Buffer> data;
        Handler<Void> end;
        Handler<Throwable> error;

        void send(String chunk) {
            data.handle(Buffer.buffer(chunk));
        }

        void finish() {
            end.handle(null);
        }

        void close() {
            error.handle(new HttpClosedException("Connection was closed"));
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            error = handler;
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            data = handler;
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> handler) {
            end = handler;
            return this;
        }
    }

    /** What the reader saw. */
    static final class Reader {
        final StringBuilder received = new StringBuilder();
        boolean ended;
        Throwable failed;
    }

    private static Reader read(ResumingRangeBody body) {
        Reader reader = new Reader();
        body.handler(chunk -> reader.received.append(chunk.toString()), () -> reader.ended = true, err -> reader.failed = err);
        body.resume();
        return reader;
    }

    /** Opens hand out these streams (or failures) in order, recording the offset each was asked for. */
    private static ResumingRangeBody body(long length, List<Object> opens, List<Long> offsets) {
        return new ResumingRangeBody(offset -> {
            offsets.add(offset);
            Object next = opens.removeFirst();
            return next instanceof Throwable t ? Future.failedFuture(t) : Future.succeededFuture((FakeStream) next);
        }, length);
    }

    @Test
    void aCloseAfterTheLastByteIsTheEndNotAFailure() {
        FakeStream stream = new FakeStream();
        ResumingRangeBody body = body(6, new ArrayList<>(List.of(stream)), new ArrayList<>());
        Reader reader = read(body);
        stream.send("abc");
        stream.send("def");
        // The server closed as soon as it sent the last byte; Vert.x reported that instead of the end.
        stream.close();
        assertThat(reader.failed).isNull();
        assertThat(reader.ended).isTrue();
        assertThat(reader.received).hasToString("abcdef");
    }

    @Test
    void aCloseHalfwayAsksForTheRest() {
        FakeStream first = new FakeStream();
        FakeStream second = new FakeStream();
        List<Long> offsets = new ArrayList<>();
        Reader reader = read(body(6, new ArrayList<>(List.of(first, second)), offsets));
        first.send("abc");
        first.close();
        second.send("def");
        second.finish();
        assertThat(offsets).containsExactly(0L, 3L);
        assertThat(reader.received).hasToString("abcdef");
        assertThat(reader.ended).isTrue();
    }

    @Test
    void aRequestThatFailsOnAClosingConnectionIsTriedAgain() {
        FakeStream stream = new FakeStream();
        List<Long> offsets = new ArrayList<>();
        // The first request went out on a pooled connection the server was just closing.
        Reader reader = read(body(3, new ArrayList<>(List.of(new HttpClosedException("Connection was closed"), stream)), offsets));
        stream.send("abc");
        stream.finish();
        assertThat(offsets).containsExactly(0L, 0L);
        assertThat(reader.failed).isNull();
        assertThat(reader.received).hasToString("abc");
    }

    @Test
    void aResumeThatFailsToOpenIsTriedAgainToo() {
        FakeStream first = new FakeStream();
        FakeStream third = new FakeStream();
        List<Long> offsets = new ArrayList<>();
        Reader reader = read(body(6, new ArrayList<>(List.of(first, new HttpClosedException("Connection was closed"), third)), offsets));
        first.send("ab");
        first.close();
        third.send("cdef");
        third.finish();
        assertThat(offsets).containsExactly(0L, 2L, 2L);
        assertThat(reader.received).hasToString("abcdef");
        assertThat(reader.ended).isTrue();
    }

    @Test
    void anAnswerThatWontChangeIsntRetried() {
        AtomicReference<Integer> opens = new AtomicReference<>(0);
        ResumingRangeBody body = new ResumingRangeBody(offset -> {
            opens.set(opens.get() + 1);
            return Future.failedFuture(new S3Client.S3Exception(403, "AccessDenied", "Access Denied"));
        }, 3);
        Reader reader = read(body);
        assertThat(opens.get()).isEqualTo(1);
        assertThat(reader.failed).hasMessageContaining("Access Denied");
    }

    @Test
    void itGivesUpAfterTheResumesRunOut() {
        List<Object> opens = new ArrayList<>();
        for (int i = 0; i <= ResumingRangeBody.MAX_RESUMES; i++) {
            opens.add(new HttpClosedException("Connection was closed"));
        }
        Reader reader = read(body(3, opens, new ArrayList<>()));
        assertThat(reader.failed).isInstanceOf(HttpClosedException.class);
        assertThat(reader.ended).isFalse();
    }
}
