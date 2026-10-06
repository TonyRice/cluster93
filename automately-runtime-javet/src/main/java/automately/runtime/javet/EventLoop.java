package automately.runtime.javet;

import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One isolate's event loop: timers set by the script, and tasks posted from other threads (a stop
 * request, a wake-up after a kill; in phase 3, incoming requests). Timers are only touched on the
 * isolate's thread; {@link #post} is safe from any thread.
 */
final class EventLoop {

    private record Timer(long dueNanos, long sequence, int id) {
    }

    private final PriorityQueue<Timer> timers = new PriorityQueue<>((a, b) -> a.dueNanos != b.dueNanos
            ? Long.compare(a.dueNanos, b.dueNanos)
            : Long.compare(a.sequence, b.sequence));
    /** Ids with a live timer; a cleared timer stays in the queue but is skipped when it comes due. */
    private final Set<Integer> live = new HashSet<>();
    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private long sequence;
    private String uncaught;
    private boolean stopWatched;

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

    Runnable pollTask() {
        return tasks.poll();
    }

    /** Waits for a posted task, at most {@code nanos} (or indefinitely if negative). */
    Runnable awaitTask(long nanos) throws InterruptedException {
        return nanos < 0 ? tasks.take() : tasks.poll(nanos, TimeUnit.NANOSECONDS);
    }

    /** The script looked at {@code ctx.stopRequested}, so a stop is something it can be waiting for. */
    void stopWatched() {
        stopWatched = true;
    }

    boolean isStopWatched() {
        return stopWatched;
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

    void post(Runnable task) {
        tasks.add(task);
    }

    /** Wakes the loop without doing anything, so it re-checks whether it was killed. */
    void wake() {
        tasks.add(() -> {
        });
    }
}
