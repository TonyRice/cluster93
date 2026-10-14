package cluster93.core.scheduler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogBookTest {

    @Test
    void keepsTheLastThousandLines() {
        LogBook book = new LogBook();
        for (int i = 1; i <= 1500; i++) {
            assertThat(book.add("line " + i)).isEqualTo(i);
        }
        assertThat(book.lines()).hasSize(NodeVerticle.MAX_LOG_LINES).first().isEqualTo("line 501");
        assertThat(book.tail().getLong("lastSeq")).isEqualTo(1500);
    }

    @Test
    void keepsAtMostAMegabyteOfCharacters() {
        LogBook book = new LogBook();
        String line = "x".repeat(LogBook.MAX_LINE_CHARS);
        for (int i = 0; i < 100; i++) {
            book.add(LogBook.clip(line));
        }
        assertThat(book.lines()).hasSize(LogBook.MAX_CHARS / LogBook.MAX_LINE_CHARS);
        assertThat(book.tail().getLong("lastSeq")).isEqualTo(100);
    }

    @Test
    void clipsALongLineAndSaysHowMuchWasCut() {
        assertThat(LogBook.clip("short")).isEqualTo("short");
        String clipped = LogBook.clip("a".repeat(100_000));
        assertThat(clipped).startsWith("a".repeat(LogBook.MAX_LINE_CHARS) + " … [")
                .endsWith((100_000 - LogBook.MAX_LINE_CHARS) + " more characters]");
        // The prelude's own marker fits in the room left for it, so a line it clipped isn't clipped again.
        String fromPrelude = "b".repeat(LogBook.MAX_LINE_CHARS) + " … [123456789 more characters]";
        assertThat(LogBook.clip(fromPrelude)).isSameAs(fromPrelude);
    }

    @Test
    void neverSplitsASurrogatePair() {
        String line = "a".repeat(LogBook.MAX_LINE_CHARS - 1) + "😀".repeat(100);
        String clipped = LogBook.clip(line);
        assertThat(clipped).startsWith("a".repeat(LogBook.MAX_LINE_CHARS - 1) + " … [");
    }
}
