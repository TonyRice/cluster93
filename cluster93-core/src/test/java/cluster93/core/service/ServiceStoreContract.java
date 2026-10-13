package cluster93.core.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Behaviour every {@link ServiceStore} must have; each implementation's test extends this. */
public abstract class ServiceStoreContract {

    /** Set by the implementation's test before each test. */
    protected ServiceStore store;

    private static ServiceDefinition service(String tenant, String name) {
        return ServiceDefinition.create(tenant, name, "script", new JsonObject(), RestartPolicy.ALWAYS, true, 1);
    }

    @Test
    public void createsOnceAndReadsBack() throws Exception {
        assertThat(await(store.create(service("acme", "web")))).isTrue();
        assertThat(await(store.create(service("acme", "web")))).isFalse();

        assertThat(await(store.get("acme", "web"))).isEqualTo(service("acme", "web"));
        assertThat(await(store.get("acme", "missing"))).isNull();
    }

    @Test
    public void listsByTenantInNameOrder() throws Exception {
        for (String name : List.of("zeta", "alpha", "mid")) {
            await(store.create(service("acme", name)));
        }
        await(store.create(service("globex", "web")));

        assertThat(await(store.list("acme"))).extracting(ServiceDefinition::name).containsExactly("alpha", "mid", "zeta");
        assertThat(await(store.all())).hasSize(4);
    }

    @Test
    public void concurrentUpdatesLoseNothing() throws Exception {
        await(store.create(service("acme", "counter")));
        List<Future<Optional<ServiceDefinition>>> updates = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            updates.add(store.update("acme", "counter", s -> s.startingRun("job-" + System.nanoTime(), 10)
                    .flatMap(started -> started.runEnded(started.currentJobId(), null, java.time.Duration.ofSeconds(60),
                            java.time.Duration.ZERO, java.time.Duration.ZERO, 10))));
        }
        await(Future.all(updates));

        assertThat(await(store.get("acme", "counter")).runs()).isEqualTo(30);
    }

    @Test
    public void deletes() throws Exception {
        await(store.create(service("acme", "web")));
        await(store.delete("acme", "web"));

        assertThat(await(store.get("acme", "web"))).isNull();
        assertThat(await(store.update("acme", "web", s -> Optional.of(s)))).isEmpty();
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
