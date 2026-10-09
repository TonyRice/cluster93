// MessageChannel, MessagePort, MessageEvent and BroadcastChannel, as Node's (D-124), within the isolate: a message is
// structuredClone'd and delivered on a later turn, as Node's ports deliver. Ports start when a 'message' listener is
// added (on('message') or onmessage) or start() is called, and queue until then. Like Node's, a port is an EventTarget
// that also has on/once/off, whose listeners get the message itself rather than the event.
(() => {
  const kState = Symbol('state');

  class MessageEvent extends Event {
    constructor(type, init = {}) {
      super(type, init);
      this[kState] = { data: init.data ?? null, origin: init.origin ?? '', lastEventId: init.lastEventId ?? '', source: init.source ?? null,
        ports: Object.freeze([...(init.ports ?? [])]) };
    }
    get data() { return this[kState].data; }
    get origin() { return this[kState].origin; }
    get lastEventId() { return this[kState].lastEventId; }
    get source() { return this[kState].source; }
    get ports() { return this[kState].ports; }
  }

  const clone = (message) => {
    try {
      return structuredClone(message);
    } catch {
      throw new DOMException(`${typeof message === 'function' ? String(message) : Object.prototype.toString.call(message)} could not be cloned.`,
        'DataCloneError');
    }
  };

  /** EventTarget plus Node's on/once/off for 'message' and 'close', whose listeners get the data. */
  class NodeEventTarget extends EventTarget {
    #wrapped = new Map();
    on(type, listener) {
      const wrapper = (event) => listener(type === 'message' ? event.data : event);
      this.#wrapped.set(listener, wrapper);
      this.addEventListener(type, wrapper);
      return this;
    }
    once(type, listener) {
      const wrapper = (event) => { this.#wrapped.delete(listener); listener(type === 'message' ? event.data : event); };
      this.#wrapped.set(listener, wrapper);
      this.addEventListener(type, wrapper, { once: true });
      return this;
    }
    off(type, listener) {
      const wrapper = this.#wrapped.get(listener);
      if (wrapper) { this.#wrapped.delete(listener); this.removeEventListener(type, wrapper); }
      return this;
    }
    addListener(type, listener) { return this.on(type, listener); }
    removeListener(type, listener) { return this.off(type, listener); }
    emit(type, arg) { this.dispatchEvent(type === 'message' ? new MessageEvent('message', { data: arg }) : new Event(type)); return true; }
  }

  class MessagePort extends NodeEventTarget {
    constructor() {
      super();
      this[kState] = { other: null, started: false, closed: false, queue: [], onmessage: null, onmessageerror: null };
    }
    postMessage(message, transfer) {
      const copy = clone(message);
      const other = this[kState].other;
      if (this[kState].closed || !other || other[kState].closed) return;
      other[kState].queue.push(copy);
      other._flush();
    }
    start() { this[kState].started = true; this._flush(); }
    close() {
      const state = this[kState];
      if (state.closed) return;
      state.closed = true;
      const other = state.other;
      setImmediate(() => this.dispatchEvent(new Event('close')));
      if (other && !other[kState].closed) other.close();
    }
    ref() { return this; }
    unref() { return this; }
    hasRef() { return !this[kState].closed; }
    addEventListener(type, listener, options) {
      super.addEventListener(type, listener, options);
      if (type === 'message' && !(this instanceof BroadcastChannel)) this.start();
    }
    get onmessage() { return this[kState].onmessage; }
    set onmessage(handler) {
      if (this[kState].onmessage) this.removeEventListener('message', this[kState].onmessage);
      this[kState].onmessage = typeof handler === 'function' ? handler : null;
      if (this[kState].onmessage) this.addEventListener('message', this[kState].onmessage);
    }
    get onmessageerror() { return this[kState].onmessageerror; }
    set onmessageerror(handler) { this[kState].onmessageerror = typeof handler === 'function' ? handler : null; }
    _flush() {
      const state = this[kState];
      if (!state.started || state.scheduled || state.queue.length === 0) return;
      state.scheduled = true;
      setImmediate(() => {
        state.scheduled = false;
        while (state.queue.length && !state.closed) this.dispatchEvent(new MessageEvent('message', { data: state.queue.shift() }));
      });
    }
    get [Symbol.toStringTag]() { return 'MessagePort'; }
  }

  class MessageChannel {
    constructor() {
      const port1 = new MessagePort();
      const port2 = new MessagePort();
      port1[kState].other = port2;
      port2[kState].other = port1;
      this.port1 = port1;
      this.port2 = port2;
    }
  }

  const channels = new Map();
  class BroadcastChannel extends NodeEventTarget {
    constructor(name) {
      if (arguments.length < 1) {
        throw Object.assign(new TypeError('The "name" argument must be specified'), { code: 'ERR_MISSING_ARGS' });
      }
      super();
      this[kState] = { name: String(name), closed: false, onmessage: null, onmessageerror: null };
      if (!channels.has(this[kState].name)) channels.set(this[kState].name, new Set());
      channels.get(this[kState].name).add(this);
    }
    get name() { return this[kState].name; }
    postMessage(message) {
      if (this[kState].closed) throw new DOMException('BroadcastChannel is closed.', 'InvalidStateError');
      const copy = clone(message);
      for (const channel of channels.get(this[kState].name) ?? []) {
        if (channel !== this) setImmediate(() => { if (!channel[kState].closed) channel.dispatchEvent(new MessageEvent('message', { data: structuredClone(copy) })); });
      }
    }
    close() {
      if (this[kState].closed) return;
      this[kState].closed = true;
      channels.get(this[kState].name)?.delete(this);
    }
    ref() { return this; }
    unref() { return this; }
    get onmessage() { return this[kState].onmessage; }
    set onmessage(handler) {
      if (this[kState].onmessage) this.removeEventListener('message', this[kState].onmessage);
      this[kState].onmessage = typeof handler === 'function' ? handler : null;
      if (this[kState].onmessage) this.addEventListener('message', this[kState].onmessage);
    }
    get onmessageerror() { return this[kState].onmessageerror; }
    set onmessageerror(handler) { this[kState].onmessageerror = typeof handler === 'function' ? handler : null; }
    get [Symbol.toStringTag]() { return 'BroadcastChannel'; }
  }

  return { MessageChannel, MessagePort, MessageEvent, BroadcastChannel };
})()
