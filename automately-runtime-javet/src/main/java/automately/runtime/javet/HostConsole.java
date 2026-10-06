package automately.runtime.javet;

import automately.core.runtime.LogSink;
import com.caoccao.javet.annotations.V8Function;

/** The only Java object a script can reach. The prelude wraps it as {@code console} and then hides it. */
public final class HostConsole {

    private final LogSink sink;

    HostConsole(LogSink sink) {
        this.sink = sink;
    }

    @V8Function(name = "log")
    public void log(String level, String line) {
        sink.log(level, line);
    }
}
