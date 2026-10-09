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

    /** Runs timer {@code id}'s callback among the next turn's I/O callbacks (D-121). */
    @V8Function(name = "nextTurn")
    public void nextTurn(int id) {
        loop.deferToNextTurn(id);
    }

    /** Whether immediates are queued for the loop's next check phase. */
    @V8Function(name = "immediates")
    public void immediates(boolean pending) {
        loop.immediatesPending(pending);
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
