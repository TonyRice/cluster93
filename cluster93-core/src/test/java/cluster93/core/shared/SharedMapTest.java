package cluster93.core.shared;

import cluster93.core.host.SharedDataKvStore;
import cluster93.core.job.SharedDataJobStore;
import cluster93.core.service.SharedDataServiceStore;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A store's answers complete on the caller's context, not on the one that built the store (D-099). Before, the API's
 * handlers continued on the store's context, answered from the wrong event loop, and under load left a CLI request
 * hanging for good.
 */
class SharedMapTest {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** A context of its own: each verticle gets the next event loop. */
    private Context deployed() throws Exception {
        CompletableFuture<Context> started = new CompletableFuture<>();
        vertx.deployVerticle(new AbstractVerticle() {
            @Override
            public void start() {
                started.complete(Vertx.currentContext());
            }
        }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return started.get();
    }

    private static <T> T on(Context context, Supplier<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        context.runOnContext(v -> result.complete(work.get()));
        return result.get(10, TimeUnit.SECONDS);
    }

    /** The context a call made on {@code caller} completes on. */
    private static Context answeredOn(Context caller, Supplier<Future<?>> call) throws Exception {
        CompletableFuture<Context> answered = new CompletableFuture<>();
        caller.runOnContext(v -> call.get().onComplete(ar -> answered.complete(Vertx.currentContext())));
        return answered.get(10, TimeUnit.SECONDS);
    }

    @Test
    void storesAnswerOnTheCallersContext() throws Exception {
        Context builder = deployed();
        Context caller = deployed();
        assertThat(caller).isNotSameAs(builder);

        SharedDataServiceStore services = on(builder, () -> new SharedDataServiceStore(vertx));
        SharedDataKvStore kv = on(builder, () -> new SharedDataKvStore(vertx));
        SharedDataJobStore jobs = on(builder, () -> new SharedDataJobStore(vertx));

        assertThat(answeredOn(caller, () -> services.get("acme", "web"))).isSameAs(caller);
        assertThat(answeredOn(caller, () -> services.get("acme", "web").compose(none -> services.all()))).isSameAs(caller);
        assertThat(answeredOn(caller, () -> kv.get("acme", "default", "k"))).isSameAs(caller);
        assertThat(answeredOn(caller, () -> jobs.get("j-1"))).isSameAs(caller);
    }
}
