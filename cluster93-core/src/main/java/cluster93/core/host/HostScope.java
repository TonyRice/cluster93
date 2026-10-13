package cluster93.core.host;

import cluster93.core.runtime.api.Registration;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What every API of one execution shares: the tenant every call is scoped to (taken from the job, never from the
 * script), the job, and the listeners to undo when the script ends.
 */
public final class HostScope {

    private final Vertx vertx;
    private final String tenant;
    private final String jobId;
    private final boolean service;
    private final List<Registration> registrations = new CopyOnWriteArrayList<>();
    private final List<Runnable> cleanups = new CopyOnWriteArrayList<>();

    public HostScope(Vertx vertx, String tenant, String jobId, boolean service) {
        this.vertx = vertx;
        this.tenant = tenant;
        this.jobId = jobId;
        this.service = service;
    }

    public Vertx vertx() {
        return vertx;
    }

    public String tenant() {
        return tenant;
    }

    public String jobId() {
        return jobId;
    }

    public boolean service() {
        return service;
    }

    /** Cancelled by {@link #close()} unless cancelled first. */
    Registration track(Registration registration) {
        Registration tracked = new Registration() {
            @Override
            public Future<Void> cancel() {
                registrations.remove(this);
                return registration.cancel();
            }
        };
        registrations.add(tracked);
        return tracked;
    }

    /** Runs when the script ends, such as discarding an unfinished upload. */
    void onClose(Runnable cleanup) {
        cleanups.add(cleanup);
    }

    void forget(Runnable cleanup) {
        cleanups.remove(cleanup);
    }

    Future<Void> close() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
        List<Future<Void>> undone = List.copyOf(registrations).stream().map(Registration::cancel).toList();
        registrations.clear();
        return Future.join(undone).mapEmpty();
    }
}
