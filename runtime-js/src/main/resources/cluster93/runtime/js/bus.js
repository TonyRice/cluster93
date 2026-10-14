// bus: BusBinding over the cluster's event bus, at most once; handlers receive (message, { topic }).
// bus.queue(name): QueueBinding, durable and at least once (D-061); a consume handler resolving acknowledges the
// message, throwing retries it later.
const onMessage = (handler) => {
  if (typeof handler !== 'function') throw new TypeError('A handler must be a function');
  return (payload) => handler(payload.message, Object.freeze({ topic: payload.topic }));
};
define('bus', Object.freeze({
  publish: (topic, message) => host.bus.publish(topic, message),
  request: (topic, message, options) => host.bus.request(topic, message, options),
  subscribe: (topic, handler) => { const h = onMessage(handler); return listen((id) => host.bus.subscribe(topic, id), h); },
  handle: (topic, handler) => { const h = onMessage(handler); return listen((id) => host.bus.handle(topic, id), h); },
  queue: (name) => {
    const queue = String(name);
    return Object.freeze({
      name: queue,
      push: (message, options) => host.queue.push(queue, message, options),
      consume: (handler, options) => {
        if (typeof handler !== 'function') throw new TypeError('A handler must be a function');
        const h = (d) => handler(d.message, Object.freeze({ id: d.id, attempt: d.attempt, enqueuedAt: d.enqueuedAt, queue: d.queue }));
        return listen((id) => host.queue.consume(queue, id, options), h);
      },
      stats: () => host.queue.stats(queue),
      dead: (options) => host.queue.dead(queue, options),
      retry: (id) => host.queue.retry(queue, id),
      discard: (id) => host.queue.discard(queue, id),
    });
  },
}));
