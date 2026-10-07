package automately.core.files;

import automately.core.runtime.HostException;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiskBlobStoreTest extends BlobStoreContract {

    @TempDir
    Path dir;

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        blobs = new DiskBlobStore(vertx, dir.resolve("blobs"), 0);
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    @Test
    void aBlobIsAFileOnlyOnceFinished() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        BlobWriter writer = await(blobs.create(id, "text/plain"));
        await(writer.write(Buffer.buffer("half")));
        try (Stream<Path> files = Files.walk(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).contains(id + ".part").doesNotContain(id);
        }
        await(writer.finish());
        try (Stream<Path> files = Files.walk(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).contains(id).doesNotContain(id + ".part");
        }
    }

    @Test
    void itRefusesWhenTheDiskIsTooFull() {
        BlobStore full = new DiskBlobStore(vertx, dir.resolve("full"), Long.MAX_VALUE);
        assertThatThrownBy(() -> await(full.create("abcdef", "text/plain")))
                .isInstanceOf(ExecutionException.class).cause().isInstanceOf(HostException.class)
                .satisfies(e -> assertThat(((HostException) e).code()).isEqualTo("insufficient_storage"));
    }

    @Test
    void anUnwritableFolderFailsTheCheck() throws Exception {
        Path file = Files.writeString(dir.resolve("not-a-folder"), "x");
        assertThatThrownBy(() -> await(new DiskBlobStore(vertx, file, 0).check()))
                .isInstanceOf(ExecutionException.class).cause().hasMessageContaining("isn't a writable folder");
    }
}
