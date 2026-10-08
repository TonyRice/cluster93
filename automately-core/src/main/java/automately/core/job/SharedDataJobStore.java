package automately.core.job;

import io.vertx.core.Future;
import automately.core.shared.SharedMap;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Keeps jobs in a Vert.x {@link AsyncMap}. In a clustered Vert.x instance this is backed by the cluster
 * manager (Hazelcast); in a plain instance it is a local in-memory map, which is what the tests use.
 * {@link #update} is a compare-and-set loop on {@link AsyncMap#replaceIfPresent} (D-011).
 */
public final class SharedDataJobStore implements JobStore {

    static final String MAP_NAME = "automately.jobs";
    static final int MAX_UPDATE_ATTEMPTS = 20;

    private final SharedMap<String, JsonObject> map;

    public SharedDataJobStore(Vertx vertx) {
        this.map = new SharedMap<>(vertx, MAP_NAME);
    }

    @Override
    public Future<Void> save(Job job) {
        return map.compose(m -> m.put(job.id(), job.toJson()));
    }

    @Override
    public Future<Job> get(String id) {
        return map.compose(m -> m.get(id)).map(json -> json == null ? null : Job.fromJson(json));
    }

    @Override
    public Future<List<Job>> list(String tenant, int limit) {
        // A full scan: this in-memory store is for development and tests; the Ignite store uses an index.
        return all().map(jobs -> jobs.stream()
                .filter(job -> job.tenant().equals(tenant))
                .sorted(Comparator.comparingLong(Job::createdAt).reversed())
                .limit(limit)
                .toList());
    }

    @Override
    public Future<List<Job>> deleteFinishedBefore(long cutoff, int limit) {
        return all().compose(jobs -> {
            List<Job> expired = jobs.stream()
                    .filter(job -> job.status().isTerminal() && job.finishedAt() != null && job.finishedAt() < cutoff)
                    .limit(limit)
                    .toList();
            return map.compose(m -> Future.all(expired.stream().map(job -> m.remove(job.id())).toList())).map(done -> expired);
        });
    }

    @Override
    public Future<List<Job>> active() {
        return all().map(jobs -> jobs.stream().filter(job -> !job.status().isTerminal()).toList());
    }

    @Override
    public Future<Optional<Job>> update(String id, Function<Job, Optional<Job>> transition) {
        return map.compose(m -> update(m, id, transition, 1));
    }

    private Future<Optional<Job>> update(AsyncMap<String, JsonObject> m, String id, Function<Job, Optional<Job>> transition, int tries) {
        return m.get(id).compose(current -> {
            if (current == null) {
                return Future.succeededFuture(Optional.empty());
            }
            Optional<Job> next = transition.apply(Job.fromJson(current));
            if (next.isEmpty()) {
                return Future.succeededFuture(Optional.empty());
            }
            return m.replaceIfPresent(id, current, next.get().toJson()).compose(replaced -> {
                if (replaced) {
                    return Future.succeededFuture(next);
                }
                if (tries >= MAX_UPDATE_ATTEMPTS) {
                    return Future.failedFuture("Gave up updating job " + id + " after " + tries + " conflicting writes");
                }
                return update(m, id, transition, tries + 1);
            });
        });
    }

    private Future<List<Job>> all() {
        return map.compose(AsyncMap::values).map(values -> values.stream().map(Job::fromJson).toList());
    }
}
