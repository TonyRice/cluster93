package automately.runtime.javet;

import com.caoccao.javet.exceptions.JavetException;

import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One isolate's event loop: timers set by the script, tasks posted from other threads (a stop request,
 * a host call's result, an inbound message or HTTP request), and the bookkeeping that says whether the
 * script can still be woken up (calls in flight, active listeners). Everything except {@link #post} and
 * {@link #wake} is only touched on the isolate's thread.
 */
final class EventLoop {

    /** Work for the isolate's thread; receives the prelude's entry points. */
    @FunctionalInterface
    interface Task {
        void run(ScriptEntryPoints js) throws JavetException;
    }

    private record Timer(long dueNanos, long sequence, int id) {
    }

    private static final Task WAKE = js -> {
    };

    private final PriorityQueue<Timer> timers = new PriorityQueue<>((a, b) -> a.dueNanos != b.dueNanos
            ? Long.compare(a.dueNanos, b.dueNanos)
            : Long.compare(a.sequence, b.sequence));
    /** Ids with a live timer; a cleared timer stays in the queue but is skipped when it comes due. */
    private final Set<Integer> live = new HashSet<>();
    private final LinkedBlockingQueue<Task> tasks = new LinkedBlockingQueue<>();
    private long sequence;
    private String uncaught;
    private boolean stopWatched;
    private int callsInFlight;
    private int listeners;
    private int openStreams;
    private boolean immediatesPending;
    /** Callbacks (timer ids) for the next turn's I/O phase, as a network read Node handles then (D-121). */
    private java.util.List<Integer> nextTurn = new java.util.ArrayList<>();

    // --- isolate thread only ---

    void schedule(int id, double delayMs) {
        long delayNanos = (long) (Math.max(1, delayMs) * 1_000_000);
        live.add(id);
        timers.add(new Timer(System.nanoTime() + delayNanos, sequence++, id));
    }

    void clear(int id) {
        live.remove(id);
    }

    /** The id of a timer that is due now, or -1. */
    int pollDue() {
        long now = System.nanoTime();
        while (!timers.isEmpty() && timers.peek().dueNanos <= now) {
            Timer timer = timers.poll();
            if (live.remove(timer.id)) {
                return timer.id;
            }
        }
        return -1;
    }

    /** Nanoseconds until the next live timer is due, or -1 if there is none. */
    long nanosUntilNextTimer() {
        while (!timers.isEmpty() && !live.contains(timers.peek().id)) {
            timers.poll();
        }
        return timers.isEmpty() ? -1 : Math.max(0, timers.peek().dueNanos - System.nanoTime());
    }

    Task pollTask() {
        return tasks.poll();
    }

    /** Waits for a posted task, at most {@code nanos} (or indefinitely if negative). */
    Task awaitTask(long nanos) throws InterruptedException {
        return nanos < 0 ? tasks.take() : tasks.poll(nanos, TimeUnit.NANOSECONDS);
    }

    void deferToNextTurn(int id) {
        nextTurn.add(id);
    }

    /** The callbacks deferred to this turn; any deferred while they run wait for the next. */
    java.util.List<Integer> takeNextTurn() {
        java.util.List<Integer> due = nextTurn;
        nextTurn = new java.util.ArrayList<>();
        return due;
    }

    void immediatesPending(boolean pending) {
        immediatesPending = pending;
    }

    boolean immediatesPending() {
        return immediatesPending;
    }

    /** The script looked at {@code ctx.stopRequested}, so a stop is something it can be waiting for. */
    void stopWatched() {
        stopWatched = true;
    }

    void callStarted() {
        callsInFlight++;
    }

    void callSettled() {
        callsInFlight--;
    }

    int callsInFlight() {
        return callsInFlight;
    }

    void listenerAdded() {
        listeners++;
    }

    void listenerRemoved() {
        listeners--;
    }

    int listeners() {
        return listeners;
    }

    void streamOpened() {
        openStreams++;
    }

    void streamClosed() {
        openStreams--;
    }

    /** Whether something outside the script's own code can still resolve its promise. */
    boolean canBeWoken() {
        return stopWatched || callsInFlight > 0 || listeners > 0 || openStreams > 0;
    }

    void reportUncaught(String error) {
        if (uncaught == null) {
            uncaught = error;
        }
    }

    String uncaught() {
        return uncaught;
    }

    // --- any thread ---

    void post(Task task) {
        tasks.add(task);
    }

    /** Wakes the loop without doing anything, so it re-checks whether it was killed. */
    void wake() {
        tasks.add(WAKE);
    }
}
