// Node's `async_hooks` (D-123), as far as the isolate can honour it. AsyncResource is complete: binding and running a
// function in its scope is all it does for libraries (on-finished, raw-body, Fastify). AsyncLocalStorage can't carry a
// value across `await`, which needs a V8 hook Javet doesn't expose, so run() and enterWith() refuse rather than lose
// the value silently (D-112); exit() and getStore() work. createHook can't see async operations: enable() refuses.

function unsupported(what) {
  const err = new Error(`${what} isn't available in the isolate: carrying context across await needs a V8 hook the isolate doesn't have`);
  err.code = 'ERR_NOT_SUPPORTED';
  return err;
}

let nextAsyncId = 2;

export class AsyncResource {
  constructor(type, options = {}) {
    if (typeof type !== 'string') {
      const err = new TypeError(`The "type" argument must be of type string. Received ${type === null ? 'null' : typeof type}`);
      err.code = 'ERR_INVALID_ARG_TYPE';
      throw err;
    }
    const trigger = typeof options === 'number' ? options : options?.triggerAsyncId;
    this._type = type;
    this._asyncId = nextAsyncId++;
    this._triggerAsyncId = typeof trigger === 'number' ? trigger : 1;
  }

  runInAsyncScope(fn, thisArg, ...args) {
    return Reflect.apply(fn, thisArg, args);
  }

  emitDestroy() { return this; }
  asyncId() { return this._asyncId; }
  triggerAsyncId() { return this._triggerAsyncId; }

  bind(fn, thisArg) {
    if (typeof fn !== 'function') {
      const err = new TypeError(`The "fn" argument must be of type function. Received ${fn === null ? 'null' : typeof fn}`);
      err.code = 'ERR_INVALID_ARG_TYPE';
      throw err;
    }
    const resource = this;
    const bound = thisArg === undefined
      ? function (...args) { return resource.runInAsyncScope(fn, this, ...args); }
      : (...args) => resource.runInAsyncScope(fn, thisArg, ...args);
    Object.defineProperties(bound, {
      length: { configurable: true, value: fn.length },
      asyncResource: { configurable: true, enumerable: true, writable: true, value: resource },
    });
    return bound;
  }

  static bind(fn, type, thisArg) {
    return new AsyncResource(type || fn.name || 'bound-anonymous-fn').bind(fn, thisArg);
  }
}

export class AsyncLocalStorage {
  constructor() { this._store = undefined; this._enabled = false; }
  getStore() { return this._store; }
  run() { throw unsupported('AsyncLocalStorage.run()'); }
  enterWith() { throw unsupported('AsyncLocalStorage.enterWith()'); }
  exit(callback, ...args) {
    const previous = this._store;
    this._store = undefined;
    try { return callback(...args); } finally { this._store = previous; }
  }
  disable() { this._store = undefined; }
  static bind(fn) { return fn; }
  static snapshot() { return (fn, ...args) => fn(...args); }
}

export function createHook(callbacks) {
  if (callbacks === null || typeof callbacks !== 'object') {
    const err = new TypeError('The "callbacks" argument must be of type object');
    err.code = 'ERR_INVALID_ARG_TYPE';
    throw err;
  }
  return {
    enable() { throw unsupported('async_hooks.createHook().enable()'); },
    disable() { return this; },
  };
}

export function executionAsyncId() { return 1; }
export function triggerAsyncId() { return 0; }
export function executionAsyncResource() { return {}; }
export const asyncWrapProviders = Object.freeze({ NONE: 0, HTTPINCOMINGMESSAGE: 21, HTTPCLIENTREQUEST: 20, TIMERWRAP: 39 });

export default { AsyncResource, AsyncLocalStorage, createHook, executionAsyncId, triggerAsyncId, executionAsyncResource, asyncWrapProviders };
