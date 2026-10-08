package automately.core.queue;

import automately.core.host.Addresses;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.eventbus.MessageConsumer;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * One consumer of a queue (D-061): claims up to {@code concurrency} messages at a time and hands each to the handler as
 * a {@link Delivery}. It claims at once when a push hints that there's work, and otherwise looks every second, backing
 * off to every 5 s while the queue stays empty (delayed messages and expired leases are found that way). While a
 * handler works, the lease is extended every third of it; if this node dies, the extensions stop and the message goes
 * back to the queue when the lease runs out.
 */
public final class QueueConsumer {

    static final long MIN_POLL_MS = 1_000;
    static final long MAX_POLL_MS = 5_000;

    /** A claimed message; exactly one of {@link #ack}, {@link #fail} or {@link #release} ends it. Any thread. */
    public final class Delivery {

        private final QueueMessage message;
        private final long leaseTimer;
        private boolean done;

        private Delivery(QueueMessage message) {
            this.message = message;
            long every = Math.max(10, service.leaseMs() / 3);
            this.leaseTimer = service.vertx().setPeriodic(every, every, id -> service.extend(message));
        }

        public QueueMessage message() {
            return message;
        }

        public void ack() {
            end(() -> service.ack(message), true);
        }

        public void fail(String error) {
            end(() -> service.fail(message, error, false), true);
        }

        /**
         * Gives the message back untried (the consumer is stopping); it doesn't count as an attempt. It doesn't claim
         * more: a consumer that hadn't seen its own stop yet would take back the message it just released (D-101).
         */
        public void release() {
            end(() -> service.fail(message, null, true), false);
        }

        private void end(java.util.function.Supplier<Future<Boolean>> outcome, boolean claimMore) {
            context.runOnContext(v -> {
                if (done) {
                    return;
                }
                done = true;
                service.vertx().cancelTimer(leaseTimer);
                outcome.get().onComplete(ar -> {
                    inFlight--;
                    if (claimMore) {
                        pump();
                    }
                });
            });
        }
    }

    private final QueueService service;
    private final String tenant;
    private final String queue;
    private final int concurrency;
    private final Consumer<Delivery> handler;
    private final String owner = UUID.randomUUID().toString();
    private final Context context;
    private MessageConsumer<Object> hints;
    private long pollTimer = -1;
    private long pollMs = MIN_POLL_MS;
    private boolean active = true;
    private boolean claiming;
    private boolean wakeAgain;
    private int inFlight;

    private QueueConsumer(QueueService service, String tenant, String queue, int concurrency, Consumer<Delivery> handler) {
        this.service = service;
        this.tenant = tenant;
        this.queue = queue;
        this.concurrency = concurrency;
        this.handler = handler;
        this.context = service.vertx().getOrCreateContext();
    }

    /** Starts consuming; completes once the push hints are being received. */
    public static Future<QueueConsumer> start(QueueService service, String tenant, String queue, int concurrency, Consumer<Delivery> handler) {
        QueueConsumer consumer = new QueueConsumer(service, tenant, queue, concurrency, handler);
        io.vertx.core.Promise<QueueConsumer> started = io.vertx.core.Promise.promise();
        consumer.context.runOnContext(v -> {
            consumer.hints = service.vertx().eventBus().consumer(Addresses.queueHint(tenant, queue), hint -> {
                consumer.pollMs = MIN_POLL_MS;
                consumer.pump();
            });
            consumer.hints.completion().onComplete(ar -> {
                consumer.schedulePoll();
                consumer.pump();
                started.complete(consumer);
            });
        });
        return started.future();
    }

    public String owner() {
        return owner;
    }

    /** Stops claiming. Deliveries in progress still end through their own ack, fail or release. */
    public Future<Void> stop() {
        io.vertx.core.Promise<Void> stopped = io.vertx.core.Promise.promise();
        context.runOnContext(v -> {
            active = false;
            if (pollTimer >= 0) {
                service.vertx().cancelTimer(pollTimer);
            }
            (hints == null ? Future.<Void>succeededFuture() : hints.unregister()).onComplete(stopped);
        });
        return stopped.future();
    }

    private void schedulePoll() {
        if (!active) {
            return;
        }
        pollTimer = service.vertx().setTimer(pollMs, id -> {
            pump();
            schedulePoll();
        });
    }

    /** Claims while there's capacity; one claim at a time. Context thread only. */
    private void pump() {
        if (!active || inFlight >= concurrency) {
            return;
        }
        if (claiming) {
            // A hint during a claim that found nothing must not be lost.
            wakeAgain = true;
            return;
        }
        claiming = true;
        wakeAgain = false;
        service.claim(tenant, queue, owner).onComplete(ar -> {
            claiming = false;
            if (ar.succeeded() && ar.result().isPresent()) {
                pollMs = MIN_POLL_MS;
                if (!active) {
                    // Stopped while claiming: hand it straight back.
                    service.fail(ar.result().get(), null, true);
                    return;
                }
                inFlight++;
                Delivery delivery = new Delivery(ar.result().get());
                try {
                    handler.accept(delivery);
                } catch (RuntimeException e) {
                    delivery.fail(String.valueOf(e.getMessage()));
                }
                pump();
            } else {
                if (ar.succeeded()) {
                    pollMs = Math.min(MAX_POLL_MS, pollMs * 2);
                }
                if (wakeAgain) {
                    pump();
                }
            }
        });
    }
}
