// The Web and Node globals libraries expect (D-106), pure JavaScript with no reach: atob/btoa, DOMException, Event,
// CustomEvent, EventTarget, AbortController/AbortSignal, structuredClone, queueMicrotask, setImmediate/clearImmediate
// and a coarse performance (whole milliseconds, as Date.now(): nothing finer for timing attacks).

// --- atob and btoa ---

const BASE64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

define('btoa', (data) => {
  const text = String(data);
  let out = '';
  for (let i = 0; i < text.length; i += 3) {
    const a = text.charCodeAt(i), b = text.charCodeAt(i + 1), c = text.charCodeAt(i + 2);
    if (a > 255 || b > 255 || c > 255) throw new DOMException('Invalid character', 'InvalidCharacterError');
    const n = (a << 16) | ((b || 0) << 8) | (c || 0);
    out += BASE64[n >> 18] + BASE64[(n >> 12) & 63] + (i + 1 < text.length ? BASE64[(n >> 6) & 63] : '=') + (i + 2 < text.length ? BASE64[n & 63] : '=');
  }
  return out;
});

define('atob', (data) => {
  let text = String(data).replace(/[\t\n\f\r ]/g, '');
  if (text.length % 4 === 0) text = text.replace(/==?$/, '');
  if (text.length % 4 === 1 || /[^A-Za-z0-9+/]/.test(text)) {
    throw new DOMException('The string to be decoded is not correctly encoded.', 'InvalidCharacterError');
  }
  let out = '';
  let bits = 0, value = 0;
  for (const ch of text) {
    value = (value << 6) | BASE64.indexOf(ch);
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((value >> bits) & 0xff);
    }
  }
  return out;
});

// --- DOMException ---

const DOM_CODES = { IndexSizeError: 1, HierarchyRequestError: 3, WrongDocumentError: 4, InvalidCharacterError: 5, NoModificationAllowedError: 7,
  NotFoundError: 8, NotSupportedError: 9, InvalidStateError: 11, SyntaxError: 12, InvalidModificationError: 13, NamespaceError: 14,
  InvalidAccessError: 15, TypeMismatchError: 17, SecurityError: 18, NetworkError: 19, AbortError: 20, URLMismatchError: 21,
  QuotaExceededError: 22, TimeoutError: 23, InvalidNodeTypeError: 24, DataCloneError: 25 };

class DOMException extends Error {
  #name;

  constructor(message = '', options = 'Error') {
    super(String(message), typeof options === 'object' && options !== null && 'cause' in options ? { cause: options.cause } : undefined);
    this.#name = typeof options === 'object' && options !== null ? String(options.name ?? 'Error') : String(options);
  }

  get name() {
    return this.#name;
  }

  get code() {
    return DOM_CODES[this.#name] ?? 0;
  }
}
for (const [name, code] of Object.entries(DOM_CODES)) {
  const constant = name.replace(/([a-z])([A-Z])/g, '$1_$2').toUpperCase().replace(/_ERROR$/, '_ERR');
  Object.defineProperty(DOMException, constant, { value: code, enumerable: true });
}
define('DOMException', DOMException);

// --- Event, CustomEvent and EventTarget ---

const STOP_IMMEDIATE = Symbol('stopImmediate');

class Event {
  #type;
  #defaultPrevented = false;
  #cancelable;
  #bubbles;
  #composed;
  #timeStamp = Date.now();
  target = null;
  currentTarget = null;

  constructor(type, options = {}) {
    if (arguments.length === 0) throw new TypeError('The "type" argument must be specified');
    this.#type = String(type);
    this.#cancelable = Boolean(options?.cancelable);
    this.#bubbles = Boolean(options?.bubbles);
    this.#composed = Boolean(options?.composed);
    this[STOP_IMMEDIATE] = false;
  }

  get type() { return this.#type; }
  get cancelable() { return this.#cancelable; }
  get bubbles() { return this.#bubbles; }
  get composed() { return this.#composed; }
  get defaultPrevented() { return this.#cancelable && this.#defaultPrevented; }
  get timeStamp() { return this.#timeStamp; }
  get isTrusted() { return false; }
  get eventPhase() { return this.currentTarget ? 2 : 0; }
  get returnValue() { return !this.defaultPrevented; }
  get cancelBubble() { return false; }
  composedPath() { return this.currentTarget ? [this.currentTarget] : []; }
  preventDefault() { this.#defaultPrevented = true; }
  stopPropagation() {}
  stopImmediatePropagation() { this[STOP_IMMEDIATE] = true; }
}
Object.assign(Event, { NONE: 0, CAPTURING_PHASE: 1, AT_TARGET: 2, BUBBLING_PHASE: 3 });

class CustomEvent extends Event {
  #detail;

  constructor(type, options = {}) {
    super(type, options);
    this.#detail = options?.detail ?? null;
  }

  get detail() { return this.#detail; }
}

const LISTENERS = Symbol('listeners');

class EventTarget {
  constructor() {
    Object.defineProperty(this, LISTENERS, { value: new Map() });
  }

  addEventListener(type, listener, options) {
    if (listener === null || listener === undefined) return;
    const once = typeof options === 'object' && options !== null && Boolean(options.once);
    const capture = typeof options === 'boolean' ? options : Boolean(options?.capture);
    const signal = typeof options === 'object' && options !== null ? options.signal : undefined;
    if (signal?.aborted) return;
    const all = this[LISTENERS].get(type) ?? [];
    if (all.some((l) => l.listener === listener && l.capture === capture)) return;
    const entry = { listener, once, capture };
    all.push(entry);
    this[LISTENERS].set(type, all);
    signal?.addEventListener('abort', () => this.removeEventListener(type, listener, { capture }), { once: true });
  }

  removeEventListener(type, listener, options) {
    const capture = typeof options === 'boolean' ? options : Boolean(options?.capture);
    const all = this[LISTENERS].get(type);
    if (!all) return;
    const at = all.findIndex((l) => l.listener === listener && l.capture === capture);
    if (at >= 0) all.splice(at, 1);
  }

  dispatchEvent(event) {
    if (!(event instanceof Event)) throw new TypeError('The "event" argument must be an instance of Event');
    event.target = this;
    event.currentTarget = this;
    for (const entry of [...(this[LISTENERS].get(event.type) ?? [])]) {
      if (entry.once) this.removeEventListener(event.type, entry.listener, { capture: entry.capture });
      try {
        if (typeof entry.listener === 'function') entry.listener.call(this, event);
        else entry.listener?.handleEvent?.(event);
      } catch (err) {
        // As in Node and browsers, a listener's error is reported, not thrown to whoever dispatched.
        Promise.reject(err);
      }
      if (event[STOP_IMMEDIATE]) break;
    }
    event.currentTarget = null;
    return !event.defaultPrevented;
  }
}

define('Event', Event);
define('CustomEvent', CustomEvent);
define('EventTarget', EventTarget);

// --- AbortController and AbortSignal ---

const SIGNAL_INIT = Symbol('signalInit');

class AbortSignal extends EventTarget {
  #aborted = false;
  #reason = undefined;
  #onabort = null;
  #onabortListener = null;

  constructor(init) {
    if (init !== SIGNAL_INIT) throw new TypeError('Illegal constructor');
    super();
  }

  // An event handler attribute: it runs in the order it was set, among the listeners, as in Node and browsers.
  get onabort() { return this.#onabort; }

  set onabort(handler) {
    if (this.#onabortListener) this.removeEventListener('abort', this.#onabortListener);
    this.#onabort = typeof handler === 'function' ? handler : null;
    this.#onabortListener = this.#onabort ? (event) => this.#onabort?.call(this, event) : null;
    if (this.#onabortListener) this.addEventListener('abort', this.#onabortListener);
  }

  get aborted() { return this.#aborted; }
  get reason() { return this.#reason; }

  throwIfAborted() {
    if (this.#aborted) throw this.#reason;
  }

  static abort(reason) {
    const signal = new AbortSignal(SIGNAL_INIT);
    signal.#aborted = true;
    signal.#reason = reason === undefined ? new DOMException('This operation was aborted', 'AbortError') : reason;
    return signal;
  }

  static timeout(ms) {
    const signal = new AbortSignal(SIGNAL_INIT);
    setTimeout(() => abortSignal(signal, new DOMException('The operation was aborted due to timeout', 'TimeoutError')), ms);
    return signal;
  }

  static any(signals) {
    const signal = new AbortSignal(SIGNAL_INIT);
    for (const s of signals) {
      if (s.aborted) {
        abortSignal(signal, s.reason);
        return signal;
      }
    }
    for (const s of signals) s.addEventListener('abort', () => abortSignal(signal, s.reason), { once: true });
    return signal;
  }

  static [Symbol.hasInstance](value) {
    return value !== null && typeof value === 'object' && #aborted in value;
  }

  static abortWith(signal, reason) {
    if (signal.#aborted) return;
    signal.#aborted = true;
    signal.#reason = reason === undefined ? new DOMException('This operation was aborted', 'AbortError') : reason;
    signal.dispatchEvent(new Event('abort'));
  }
}
// Kept here and taken off the class, so only this file can abort a signal without its controller.
const abortSignal = AbortSignal.abortWith;
delete AbortSignal.abortWith;

class AbortController {
  #signal = new AbortSignal(SIGNAL_INIT);

  get signal() { return this.#signal; }

  abort(reason) {
    abortSignal(this.#signal, reason);
  }
}

define('AbortSignal', AbortSignal);
define('AbortController', AbortController);

// --- structuredClone ---

const cloneError = (what) => new DOMException(what + ' could not be cloned.', 'DataCloneError');
const ERRORS = { Error, EvalError, RangeError, ReferenceError, SyntaxError, TypeError, URIError };

function cloneValue(value, seen) {
  if (value === null || (typeof value !== 'object' && typeof value !== 'function')) {
    if (typeof value === 'symbol') throw cloneError(String(value));
    return value;
  }
  if (typeof value === 'function') throw cloneError(String(value).slice(0, 40));
  if (seen.has(value)) return seen.get(value);
  const tag = Object.prototype.toString.call(value);
  let copy;
  switch (tag) {
    case '[object Date]':
      copy = new Date(value.getTime());
      break;
    case '[object RegExp]':
      copy = new RegExp(value.source, value.flags);
      break;
    case '[object Boolean]':
    case '[object Number]':
    case '[object String]':
    case '[object BigInt]':
      copy = Object(value.valueOf());
      break;
    case '[object ArrayBuffer]':
      copy = value.slice(0);
      break;
    case '[object DataView]':
      copy = new DataView(cloneValue(value.buffer, seen), value.byteOffset, value.byteLength);
      break;
    case '[object Map]':
      copy = new Map();
      seen.set(value, copy);
      for (const [k, v] of value) copy.set(cloneValue(k, seen), cloneValue(v, seen));
      return copy;
    case '[object Set]':
      copy = new Set();
      seen.set(value, copy);
      for (const v of value) copy.add(cloneValue(v, seen));
      return copy;
    case '[object Error]': {
      const Kind = ERRORS[value.name] ?? Error;
      copy = Object.create(Kind.prototype);
      seen.set(value, copy);
      Object.defineProperty(copy, 'message', { value: String(value.message), writable: true, configurable: true });
      if (value.stack !== undefined) Object.defineProperty(copy, 'stack', { value: String(value.stack), writable: true, configurable: true });
      if ('cause' in value) Object.defineProperty(copy, 'cause', { value: cloneValue(value.cause, seen), writable: true, configurable: true });
      return copy;
    }
    default:
      if (ArrayBuffer.isView(value)) {
        const buffer = cloneValue(value.buffer, seen);
        copy = new value.constructor(buffer, value.byteOffset, value.length);
        break;
      }
      if (tag === '[object Promise]' || tag === '[object WeakMap]' || tag === '[object WeakSet]' || tag === '[object WeakRef]') {
        throw cloneError('#<' + tag.slice(8, -1) + '>');
      }
      copy = Array.isArray(value) ? new Array(value.length) : {};
      seen.set(value, copy);
      for (const key of Object.keys(value)) copy[key] = cloneValue(value[key], seen);
      return copy;
  }
  seen.set(value, copy);
  return copy;
}

define('structuredClone', (value, options) => {
  const clone = cloneValue(value, new Map());
  for (const transferred of options?.transfer ?? []) {
    // Transferring detaches the original where V8 can (ArrayBuffer.prototype.transfer); the clone already has its own copy.
    if (transferred instanceof ArrayBuffer && typeof transferred.transfer === 'function') transferred.transfer();
  }
  return clone;
});

// --- microtasks, immediates and performance ---

define('queueMicrotask', (callback) => {
  if (typeof callback !== 'function') throw new TypeError('The "callback" argument must be of type function');
  Promise.resolve().then(callback);
});

define('setImmediate', (callback, ...args) => setTimeout(callback, 0, ...args));
define('clearImmediate', (id) => clearTimeout(id));

const timeOrigin = Date.now();
const marks = [];
define('performance', Object.freeze({
  timeOrigin,
  now: () => Date.now() - timeOrigin,
  mark: (name, options) => {
    const entry = Object.freeze({ name: String(name), entryType: 'mark', startTime: options?.startTime ?? Date.now() - timeOrigin, duration: 0,
      detail: options?.detail ?? null });
    if (marks.length < 10000) marks.push(entry);
    return entry;
  },
  measure: (name, start, end) => {
    const at = (m, fallback) => (typeof m === 'number' ? m : m === undefined ? fallback : marks.findLast((e) => e.name === m)?.startTime ?? fallback);
    const startTime = at(typeof start === 'object' && start !== null ? start.start : start, 0);
    const endTime = at(typeof start === 'object' && start !== null ? start.end : end, Date.now() - timeOrigin);
    const entry = Object.freeze({ name: String(name), entryType: 'measure', startTime, duration: endTime - startTime, detail: null });
    if (marks.length < 10000) marks.push(entry);
    return entry;
  },
  getEntries: () => marks.slice(),
  getEntriesByName: (name, type) => marks.filter((e) => e.name === name && (type === undefined || e.entryType === type)),
  getEntriesByType: (type) => marks.filter((e) => e.entryType === type),
  clearMarks: (name) => { for (let i = marks.length - 1; i >= 0; i--) if (marks[i].entryType === 'mark' && (name === undefined || marks[i].name === name)) marks.splice(i, 1); },
  clearMeasures: (name) => { for (let i = marks.length - 1; i >= 0; i--) if (marks[i].entryType === 'measure' && (name === undefined || marks[i].name === name)) marks.splice(i, 1); },
  toJSON: () => ({ timeOrigin }),
}));
