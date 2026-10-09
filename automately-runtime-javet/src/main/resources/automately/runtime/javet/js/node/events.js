// A stand-in for Node's `events` (D-089): EventEmitter as Node has it, a constructor function, so code written as
// `EventEmitter.call(this)` with `util.inherits` works as well as `class X extends EventEmitter`.

export function EventEmitter(options) {
  EventEmitter.init.call(this, options);
}

EventEmitter.EventEmitter = EventEmitter;
EventEmitter.defaultMaxListeners = 10;
EventEmitter.errorMonitor = Symbol('events.errorMonitor');

EventEmitter.init = function init() {
  if (this._events === undefined || this._events === Object.getPrototypeOf(this)._events) {
    this._events = Object.create(null);
    this._eventsCount = 0;
  }
  this._maxListeners = this._maxListeners || undefined;
};

function events(emitter) {
  if (emitter._events === undefined) {
    EventEmitter.init.call(emitter);
  }
  return emitter._events;
}

function checkListener(listener) {
  if (typeof listener !== 'function') {
    throw new TypeError('The "listener" argument must be of type function. Received ' + (listener === null ? 'null' : typeof listener));
  }
}

function add(emitter, name, listener, prepend) {
  checkListener(listener);
  const all = events(emitter);
  if (all.newListener !== undefined) {
    emitter.emit('newListener', name, listener.listener ? listener.listener : listener);
  }
  const list = all[name];
  if (list === undefined) {
    all[name] = [listener];
    emitter._eventsCount++;
  } else if (prepend) {
    list.unshift(listener);
  } else {
    list.push(listener);
  }
  return emitter;
}

function onceWrapper(emitter, name, listener) {
  const wrapped = function (...args) {
    emitter.removeListener(name, wrapped);
    return listener.apply(this, args);
  };
  wrapped.listener = listener;
  return wrapped;
}

const proto = EventEmitter.prototype;
proto._events = undefined;
proto._eventsCount = 0;
proto._maxListeners = undefined;

proto.setMaxListeners = function setMaxListeners(n) {
  if (typeof n !== 'number' || n < 0 || Number.isNaN(n)) {
    throw new RangeError('The value of "n" is out of range. It must be a non-negative number. Received ' + n);
  }
  this._maxListeners = n;
  return this;
};

proto.getMaxListeners = function getMaxListeners() {
  return this._maxListeners === undefined ? EventEmitter.defaultMaxListeners : this._maxListeners;
};

proto.emit = function emit(name, ...args) {
  const all = events(this);
  if (name === 'error' && all[EventEmitter.errorMonitor] !== undefined) {
    this.emit(EventEmitter.errorMonitor, ...args);
  }
  const list = all[name];
  if (list === undefined) {
    if (name === 'error') {
      const err = args[0];
      if (err instanceof Error) {
        throw err;
      }
      const unhandled = new Error('Unhandled error.' + (err === undefined ? '' : ' (' + String(err) + ')'));
      unhandled.context = err;
      throw unhandled;
    }
    return false;
  }
  for (const listener of list.slice()) {
    listener.apply(this, args);
  }
  return true;
};

proto.addListener = function addListener(name, listener) {
  return add(this, name, listener, false);
};
proto.on = proto.addListener;

proto.prependListener = function prependListener(name, listener) {
  return add(this, name, listener, true);
};

proto.once = function once(name, listener) {
  checkListener(listener);
  return add(this, name, onceWrapper(this, name, listener), false);
};

proto.prependOnceListener = function prependOnceListener(name, listener) {
  checkListener(listener);
  return add(this, name, onceWrapper(this, name, listener), true);
};

proto.removeListener = function removeListener(name, listener) {
  checkListener(listener);
  const all = events(this);
  const list = all[name];
  if (list === undefined) {
    return this;
  }
  for (let i = list.length - 1; i >= 0; i--) {
    if (list[i] === listener || list[i].listener === listener) {
      const removed = list.splice(i, 1)[0];
      if (list.length === 0) {
        delete all[name];
        this._eventsCount--;
      }
      if (all.removeListener !== undefined) {
        this.emit('removeListener', name, removed.listener || removed);
      }
      break;
    }
  }
  return this;
};
proto.off = proto.removeListener;

proto.removeAllListeners = function removeAllListeners(name) {
  const all = events(this);
  if (name === undefined) {
    for (const key of Reflect.ownKeys(all)) {
      if (key !== 'removeListener') {
        this.removeAllListeners(key);
      }
    }
    this.removeAllListeners('removeListener');
    this._events = Object.create(null);
    this._eventsCount = 0;
    return this;
  }
  const list = all[name];
  if (list !== undefined) {
    for (let i = list.length - 1; i >= 0; i--) {
      this.removeListener(name, list[i]);
    }
  }
  return this;
};

proto.listeners = function listeners(name) {
  const list = events(this)[name];
  return list === undefined ? [] : list.map((l) => l.listener || l);
};

proto.rawListeners = function rawListeners(name) {
  const list = events(this)[name];
  return list === undefined ? [] : list.slice();
};

proto.listenerCount = function listenerCount(name) {
  const list = events(this)[name];
  return list === undefined ? 0 : list.length;
};

proto.eventNames = function eventNames() {
  return this._eventsCount > 0 ? Reflect.ownKeys(this._events) : [];
};

EventEmitter.listenerCount = (emitter, name) => emitter.listenerCount(name);

function abortError(signal) {
  const err = new Error('The operation was aborted', { cause: signal?.reason });
  err.name = 'AbortError';
  err.code = 'ABORT_ERR';
  return err;
}

/**
 * Resolves with the event's arguments the next time it's emitted; rejects if 'error' comes first, or if
 * {@code options.signal} aborts.
 */
export function once(emitter, name, options = {}) {
  const signal = options?.signal;
  if (signal?.aborted) return Promise.reject(abortError(signal));
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      emitter.removeListener(name, onEvent);
      if (name !== 'error') emitter.removeListener('error', onError);
      signal?.removeEventListener('abort', onAbort);
    };
    const onError = (err) => { cleanup(); reject(err); };
    const onEvent = (...args) => { cleanup(); resolve(args); };
    const onAbort = () => { cleanup(); reject(abortError(signal)); };
    emitter.on(name, onEvent);
    if (name !== 'error') emitter.on('error', onError);
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

/**
 * An async iterator over the event's arguments, each emission an array, as Node's {@code events.on} (D-114). It throws
 * an 'error' event, ends on one of {@code options.close}'s events or when the loop breaks, and rejects with an
 * AbortError if {@code options.signal} aborts. Emissions wait in a queue until the loop asks for them.
 */
export function on(emitter, name, options = {}) {
  const signal = options?.signal;
  if (signal?.aborted) throw abortError(signal);
  const queue = [];
  const waiting = [];
  let error = null;
  let finished = false;

  const settle = () => {
    while (waiting.length && (queue.length || error || finished)) {
      const { resolve, reject } = waiting.shift();
      if (queue.length) resolve({ value: queue.shift(), done: false });
      else if (error) { const err = error; error = null; finished = true; reject(err); }
      else resolve({ value: undefined, done: true });
    }
  };
  const onEvent = (...args) => { queue.push(args); settle(); };
  const onError = (err) => { error = err; cleanup(); settle(); };
  const onClose = () => { finished = true; cleanup(); settle(); };
  const onAbort = () => { error = abortError(signal); cleanup(); settle(); };
  const cleanup = () => {
    emitter.removeListener(name, onEvent);
    if (name !== 'error') emitter.removeListener('error', onError);
    for (const event of options?.close ?? []) emitter.removeListener(event, onClose);
    signal?.removeEventListener('abort', onAbort);
  };

  emitter.on(name, onEvent);
  if (name !== 'error') emitter.on('error', onError);
  for (const event of options?.close ?? []) emitter.on(event, onClose);
  signal?.addEventListener('abort', onAbort, { once: true });

  return {
    next() {
      if (queue.length) return Promise.resolve({ value: queue.shift(), done: false });
      if (error) { const err = error; error = null; finished = true; return Promise.reject(err); }
      if (finished) return Promise.resolve({ value: undefined, done: true });
      return new Promise((resolve, reject) => { waiting.push({ resolve, reject }); });
    },
    return() {
      finished = true;
      cleanup();
      settle();
      return Promise.resolve({ value: undefined, done: true });
    },
    throw(err) {
      error = err;
      cleanup();
      settle();
      return Promise.reject(err);
    },
    [Symbol.asyncIterator]() { return this; },
  };
}
EventEmitter.on = on;
EventEmitter.once = once;

export const errorMonitor = EventEmitter.errorMonitor;
export const defaultMaxListeners = EventEmitter.defaultMaxListeners;

export default EventEmitter;
