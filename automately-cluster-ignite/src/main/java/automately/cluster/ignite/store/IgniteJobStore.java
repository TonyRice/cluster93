package automately.cluster.ignite.store;

import automately.core.job.Job;
import automately.core.job.JobStatus;
import automately.core.job.JobStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.ignite.Ignite;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Jobs in a durable, replicated table, indexed by tenant and creation time, and by status. */
public final class IgniteJobStore implements JobStore {

    static final Table.Definition TABLE = new Table.Definition("JOBS",
            Table.columns("tenant", String.class, "status", String.class, "createdAt", Long.class, "finishedAt", Long.class),
            List.of(Table.index("tenant", "createdAt"), Table.index("status", "finishedAt")));

    private final Table table;

    public IgniteJobStore(Vertx vertx, Ignite ignite) {
        this.table = new Table(vertx, ignite, TABLE, json -> {
            Map<String, Object> columns = new java.util.HashMap<>();
            columns.put("tenant", json.getString("tenant"));
            columns.put("status", json.getString("status"));
            columns.put("createdAt", json.getLong("createdAt"));
            columns.put("finishedAt", json.getLong("finishedAt"));
            return columns;
        });
    }

    @Override
    public Future<Void> save(Job job) {
        return table.put(job.id(), job.toJson());
    }

    @Override
    public Future<Job> get(String id) {
        return table.get(id).map(json -> json == null ? null : Job.fromJson(json));
    }

    @Override
    public Future<List<Job>> list(String tenant, int limit) {
        return table.queryJson("SELECT json FROM JOBS WHERE tenant = ? ORDER BY createdAt DESC LIMIT ?", tenant, limit)
                .map(rows -> rows.stream().map(Job::fromJson).toList());
    }

    @Override
    public Future<List<Job>> active() {
        return table.queryJson("SELECT json FROM JOBS WHERE status IN (?, ?)", JobStatus.QUEUED.name(), JobStatus.RUNNING.name())
                .map(rows -> rows.stream().map(Job::fromJson).toList());
    }

    @Override
    public Future<Optional<Job>> update(String id, Function<Job, Optional<Job>> transition) {
        return table.update(id, json -> transition.apply(Job.fromJson(json)).map(Job::toJson))
                .map(updated -> updated.map(Job::fromJson));
    }

    @Override
    public Future<List<Job>> deleteFinishedBefore(long cutoff, int limit) {
        return table.queryJson("SELECT json FROM JOBS WHERE finishedAt IS NOT NULL AND finishedAt < ? LIMIT ?", cutoff, limit)
                .compose(rows -> {
                    List<Job> expired = rows.stream().map(Job::fromJson).toList();
                    return Future.all(expired.stream().map(job -> table.remove(job.id())).toList()).map(done -> expired);
                });
    }

}
