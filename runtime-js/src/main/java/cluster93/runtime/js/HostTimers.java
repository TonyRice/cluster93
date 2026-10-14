package cluster93.runtime.js;


/** Backs {@code setTimeout}/{@code setInterval}: the prelude keeps the callbacks, this keeps the schedule. */
public final class HostTimers {

    private final EventLoop loop;

    HostTimers(EventLoop loop) {
        this.loop = loop;
    }

    @HostFunction(name = "timer")
    public void timer(int id, double delayMs) {
        loop.schedule(id, delayMs);
    }

    /** Runs timer {@code id}'s callback among the next turn's I/O callbacks (D-121). */
    @HostFunction(name = "nextTurn")
    public void nextTurn(int id) {
        loop.deferToNextTurn(id);
    }

    /** Whether immediates are queued for the loop's next check phase. */
    @HostFunction(name = "immediates")
    public void immediates(boolean pending) {
        loop.immediatesPending(pending);
    }

    @HostFunction(name = "clearTimer")
    public void clearTimer(int id) {
        loop.clear(id);
    }

    @HostFunction(name = "watchStop")
    public void watchStop() {
        loop.stopWatched();
    }

    @HostFunction(name = "uncaught")
    public void uncaught(String error) {
        loop.reportUncaught(error);
    }
}
