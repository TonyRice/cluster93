package cluster93.core.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a failure carries beyond its message (D-181): a failure the host expected stays as it was — a cheap thing with a
 * name, a code and the script's message — while one it did not expect also carries what it failed with and the host
 * frames that classified it, which is what an operator has to go on when a job says only "fetch failed".
 */
class HostExceptionTest {

    @Test
    void anExpectedFailureStaysStackless() {
        HostException invalid = HostException.invalid("A queue name must match the pattern");
        HostException limit = HostException.limit("The message is over 65536 bytes of JSON");

        assertThat(invalid.errorName()).isEqualTo("TypeError");
        assertThat(invalid.code()).isEqualTo("invalid_argument");
        assertThat(invalid.getMessage()).isEqualTo("A queue name must match the pattern");
        assertThat(limit.errorName()).isEqualTo("RangeError");
        assertThat(limit.code()).isEqualTo("limit_exceeded");

        // Routine failures are the common case: capturing a stack for each would cost every bad argument.
        assertThat(invalid.hostStack()).isNull();
        assertThat(limit.hostStack()).isNull();
    }

    @Test
    void anUnexpectedFailureCarriesTheClassAndTheFramesThatClassifiedIt() {
        IOException cause = new IOException("Connection was closed");
        HostException failure = HostException.host("TypeError", "network_error", "fetch failed: Connection was closed", cause);

        // The script reads exactly what it read before, down to the message.
        assertThat(failure.errorName()).isEqualTo("TypeError");
        assertThat(failure.code()).isEqualTo("network_error");
        assertThat(failure.getMessage()).isEqualTo("fetch failed: Connection was closed");
        assertThat(failure.getCause()).isSameAs(cause);

        // An operator gets the frame that classified it (this test), the class is not the messenger's own, and the
        // failure itself is named last, as a cause is.
        assertThat(failure.hostStack())
                .startsWith("    at cluster93.core.runtime.HostExceptionTest.")
                .doesNotContain("HostException.hostStack")
                .endsWith("    caused by java.io.IOException: Connection was closed");
    }

    @Test
    void theHostStackIsBoundedAndACauselessFailureIsJustItsClass() {
        assertThat(deep(30).hostStack().lines().count()).isLessThanOrEqualTo(13);

        HostException bare = HostException.host("Error", "host_error", "Something failed", new IOException());
        assertThat(bare.hostStack()).endsWith("    caused by java.io.IOException");
    }

    /** A failure classified {@code depth} calls down, so the carried frames outnumber the cap. */
    private static HostException deep(int depth) {
        return depth == 0
                ? HostException.host("Error", "host_error", "Something failed", new IOException("deep"))
                : deep(depth - 1);
    }
}
