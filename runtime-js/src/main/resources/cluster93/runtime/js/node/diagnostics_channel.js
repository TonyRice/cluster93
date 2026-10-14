// A stand-in for Node's `diagnostics_channel` (D-114): named channels a library publishes to and tools subscribe to,
// and tracing channels around sync, promise and callback work. Pure: nothing leaves the isolate. got, mysql2, ioredis
// and Fastify import it. A subscriber that throws doesn't stop the publisher; the error ends the job, as an uncaught
// exception ends Node.
const reportUncaught = globalThis[Symbol.for('cluster93.uncaught')];
const channels = new Map();

function validateName(name) {
  if (typeof name !== 'string' && typeof name !== 'symbol') {
    const err = new TypeError('The "channel" argument must be of type string or symbol');
    err.code = 'ERR_INVALID_ARG_TYPE';
    throw err;
  }
}

function validateFunction(fn, label) {
  if (typeof fn !== 'function') {
    const err = new TypeError(`The "${label}" argument must be of type function`);
    err.code = 'ERR_INVALID_ARG_TYPE';
    throw err;
  }
}

export class Channel {
  constructor(name) {
    this.name = name;
    this._subscribers = [];
    this._stores = new Map();
  }

  get hasSubscribers() {
    return this._subscribers.length > 0 || this._stores.size > 0;
  }

  subscribe(subscription) {
    validateFunction(subscription, 'subscription');
    this._subscribers = [...this._subscribers, subscription];
  }

  unsubscribe(subscription) {
    const index = this._subscribers.indexOf(subscription);
    if (index === -1) return false;
    this._subscribers = [...this._subscribers.slice(0, index), ...this._subscribers.slice(index + 1)];
    return true;
  }

  publish(data) {
    for (const subscriber of this._subscribers) {
      try {
        subscriber(data, this.name);
      } catch (e) {
        // Node rethrows it on the next tick, so the publisher carries on.
        queueMicrotask(() => reportUncaught(e, `a diagnostics_channel subscriber of "${String(this.name)}"`));
      }
    }
  }

  bindStore(store, transform) {
    this._stores.set(store, transform);
  }

  unbindStore(store) {
    return this._stores.delete(store);
  }

  runStores(data, fn, thisArg, ...args) {
    // Stores (AsyncLocalStorage) can't carry a value across await in the isolate, so each runs its store synchronously.
    let run = () => {
      this.publish(data);
      return Reflect.apply(fn, thisArg, args);
    };
    for (const [store, transform] of this._stores) {
      const next = run;
      let value = data;
      if (transform) {
        try { value = transform(data); } catch (e) { queueMicrotask(() => reportUncaught(e, 'a diagnostics_channel store transform')); continue; }
      }
      run = () => store.run(value, next);
    }
    return run();
  }
}

export function channel(name) {
  validateName(name);
  let found = channels.get(name);
  if (!found) {
    found = new Channel(name);
    channels.set(name, found);
  }
  return found;
}

export function hasSubscribers(name) {
  validateName(name);
  return channels.get(name)?.hasSubscribers ?? false;
}

export function subscribe(name, subscription) {
  channel(name).subscribe(subscription);
}

export function unsubscribe(name, subscription) {
  validateName(name);
  return channels.get(name)?.unsubscribe(subscription) ?? false;
}

const TRACING_EVENTS = ['start', 'end', 'asyncStart', 'asyncEnd', 'error'];

export class TracingChannel {
  constructor(nameOrChannels) {
    if (typeof nameOrChannels === 'string') {
      for (const event of TRACING_EVENTS) this[event] = channel(`tracing:${nameOrChannels}:${event}`);
    } else if (nameOrChannels && typeof nameOrChannels === 'object') {
      for (const event of TRACING_EVENTS) {
        if (!(nameOrChannels[event] instanceof Channel)) {
          const err = new TypeError(`The "nameOrChannels.${event}" property must be an instance of Channel`);
          err.code = 'ERR_INVALID_ARG_TYPE';
          throw err;
        }
        this[event] = nameOrChannels[event];
      }
    } else {
      const err = new TypeError('The "nameOrChannels" argument must be of type string or an instance of TracingChannel or Object');
      err.code = 'ERR_INVALID_ARG_TYPE';
      throw err;
    }
  }

  get hasSubscribers() {
    return TRACING_EVENTS.some((event) => this[event].hasSubscribers);
  }

  subscribe(handlers) {
    for (const event of TRACING_EVENTS) if (handlers[event]) this[event].subscribe(handlers[event]);
  }

  unsubscribe(handlers) {
    let done = true;
    for (const event of TRACING_EVENTS) if (handlers[event] && !this[event].unsubscribe(handlers[event])) done = false;
    return done;
  }

  traceSync(fn, context = {}, thisArg, ...args) {
    if (!this.hasSubscribers) return Reflect.apply(fn, thisArg, args);
    const { start, end, error } = this;
    return start.runStores(context, () => {
      try {
        const result = Reflect.apply(fn, thisArg, args);
        context.result = result;
        return result;
      } catch (err) {
        context.error = err;
        error.publish(context);
        throw err;
      } finally {
        end.publish(context);
      }
    });
  }

  tracePromise(fn, context = {}, thisArg, ...args) {
    if (!this.hasSubscribers) return Reflect.apply(fn, thisArg, args);
    const { start, end, asyncStart, asyncEnd, error } = this;
    const resolve = (result) => {
      context.result = result;
      asyncStart.publish(context);
      asyncEnd.publish(context);
      return result;
    };
    const reject = (err) => {
      context.error = err;
      error.publish(context);
      asyncStart.publish(context);
      asyncEnd.publish(context);
      throw err;
    };
    return start.runStores(context, () => {
      try {
        let promise = Reflect.apply(fn, thisArg, args);
        if (!(promise instanceof Promise)) promise = Promise.resolve(promise);
        return promise.then(resolve, reject);
      } catch (err) {
        context.error = err;
        error.publish(context);
        throw err;
      } finally {
        end.publish(context);
      }
    });
  }

  traceCallback(fn, position = -1, context = {}, thisArg, ...args) {
    if (!this.hasSubscribers) return Reflect.apply(fn, thisArg, args);
    const { start, end, asyncStart, asyncEnd, error } = this;
    const at = position < 0 ? args.length + position : position;
    const callback = args[at];
    validateFunction(callback, 'callback');
    args[at] = function wrapped(err, res) {
      if (err) {
        context.error = err;
        error.publish(context);
      } else {
        context.result = res;
      }
      return asyncStart.runStores(context, () => {
        try {
          return Reflect.apply(callback, this, arguments);
        } finally {
          asyncEnd.publish(context);
        }
      });
    };
    return start.runStores(context, () => {
      try {
        return Reflect.apply(fn, thisArg, args);
      } catch (err) {
        context.error = err;
        error.publish(context);
        throw err;
      } finally {
        end.publish(context);
      }
    });
  }
}

export function tracingChannel(nameOrChannels) {
  return new TracingChannel(nameOrChannels);
}

export default { channel, hasSubscribers, subscribe, unsubscribe, tracingChannel, Channel };
