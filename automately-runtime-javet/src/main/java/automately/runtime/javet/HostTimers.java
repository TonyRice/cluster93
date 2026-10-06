package automately.runtime.javet;

import com.caoccao.javet.annotations.V8Function;

/** Backs {@code setTimeout}/{@code setInterval}: the prelude keeps the callbacks, this keeps the schedule. */
public final class HostTimers {

    private final EventLoop loop;

    HostTimers(EventLoop loop) {
        this.loop = loop;
    }

    @V8Function(name = "timer")
    public void timer(int id, double delayMs) {
        loop.schedule(id, delayMs);
    }

    @V8Function(name = "clearTimer")
    public void clearTimer(int id) {
        loop.clear(id);
    }

    @V8Function(name = "watchStop")
    public void watchStop() {
        loop.stopWatched();
    }

    @V8Function(name = "uncaught")
    public void uncaught(String error) {
        loop.reportUncaught(error);
    }
}
