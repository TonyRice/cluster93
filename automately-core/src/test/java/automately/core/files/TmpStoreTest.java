package automately.core.files;

import automately.core.runtime.HostException;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A job's /tmp (D-079): folders, limits, spilling to disk, and nothing left behind. */
class TmpStoreTest {

    @TempDir
    Path data;

    @Test
    void filesAndFoldersBehaveLikeADisk() {
        TmpStore tmp = new TmpStore(null, 1024, 1024, 0);
        tmp.write("a/b/c.txt", "hello".getBytes());
        tmp.mkdir("a/empty", false);

        assertThat(new String(tmp.read("a/b/c.txt"))).isEqualTo("hello");
        assertThat(tmp.list("a").stream().map(e -> ((JsonObject) e).getString("name"))).containsExactly("b", "empty");
        assertThat(tmp.stat("a/b").getString("kind")).isEqualTo("dir");
        assertThatThrownBy(() -> tmp.rmdir("a", false)).isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("not_empty"));
        tmp.rename("a/b", "z", false);
        assertThat(new String(tmp.read("z/c.txt"))).isEqualTo("hello");
        assertThat(tmp.stat("a/b")).isNull();
        assertThat(tmp.rmdir("a", true)).isTrue();
        assertThatThrownBy(() -> tmp.read("a/empty")).isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("not_found"));
    }

    @Test
    void withoutADataDirectoryItsMemoryIsTheLimit() {
        TmpStore tmp = new TmpStore(null, 100, 100, 0);
        tmp.write("one", new byte[60]);

        assertThatThrownBy(() -> tmp.write("two", new byte[60])).isInstanceOfSatisfying(HostException.class,
                e -> assertThat(e.code()).isEqualTo("limit_exceeded"));
        tmp.write("one", new byte[90]);
        assertThat(tmp.size()).as("replacing frees the old").isEqualTo(90);
    }

    @Test
    void beyondMemoryItSpillsToDiskAndCleansUp() throws Exception {
        Path spill = data.resolve("tmp").resolve("job-1");
        TmpStore tmp = new TmpStore(spill, 100, 10_000, 0);
        tmp.write("small", new byte[80]);
        tmp.write("big", new byte[500]);

        assertThat(tmp.inMemory()).isEqualTo(80);
        assertThat(Files.list(spill).count()).as("the big one is on disk").isEqualTo(1);
        assertThat(tmp.read("big")).hasSize(500);
        assertThatThrownBy(() -> tmp.write("huge", new byte[20_000])).isInstanceOfSatisfying(HostException.class,
                e -> assertThat(e.code()).isEqualTo("limit_exceeded"));

        tmp.close();
        assertThat(Files.exists(spill)).as("gone with the job").isFalse();
        assertThatThrownBy(() -> tmp.write("late", new byte[1])).isInstanceOfSatisfying(HostException.class,
                e -> assertThat(e.code()).isEqualTo("unavailable"));
    }
}
