// Streams: chunks arrive from Java as Uint8Arrays; reading one acknowledges it, which is the backpressure (HostBridge).
const streams = new Map();
const readable = (id) => {
  const st = { queue: [], waiter: null, done: false, error: null, taken: false };
  streams.set(id, st);
  const finish = () => { if (!st.done) { st.done = true; streams.delete(id); host.cancelStream(id); } };
  const iterator = {
    next() {
      if (st.queue.length) { const value = st.queue.shift(); host.ack(id); return Promise.resolve({ value, done: false }); }
      if (st.error) return Promise.reject(st.error);
      if (st.done) return Promise.resolve({ value: undefined, done: true });
      return new Promise((resolve, reject) => { st.waiter = { resolve, reject }; });
    },
    return() { finish(); return Promise.resolve({ value: undefined, done: true }); },
    [Symbol.asyncIterator]() { return this; },
  };
  return Object.freeze({
    [Symbol.asyncIterator]() {
      if (st.taken) throw new TypeError('This body has already been read');
      st.taken = true;
      return iterator;
    },
    cancel: finish,
  });
};

// Called by the event loop: the next chunk of stream `id`, or its end if `bytes` is null.
const chunk = (id, bytes) => {
  const st = streams.get(id);
  if (!st) return;
  if (bytes === null || bytes === undefined) {
    st.done = true;
    streams.delete(id);
    if (st.waiter) { const w = st.waiter; st.waiter = null; w.resolve({ value: undefined, done: true }); }
    return;
  }
  if (st.waiter) { const w = st.waiter; st.waiter = null; host.ack(id); w.resolve({ value: bytes, done: false }); }
  else st.queue.push(bytes);
};
// The error a reader gets when the host fails a stream: the same shape a failed call gets (name, code and message,
// D-025) plus where the host failed on the stack, so a job's failure names it (D-181). The host's message is unchanged.
const streamFailure = (name, code, message, hostStack) => {
  const Kind = name === 'TypeError' ? TypeError : name === 'RangeError' ? RangeError : Error;
  const error = new Kind(message);
  if (Kind === Error && name !== 'Error') error.name = name;
  error.code = code;
  if (hostStack) error.stack = error.stack ? `${error.stack}\n${hostStack}` : hostStack;
  return error;
};
const streamError = (id, name, code, message, hostStack) => {
  const st = streams.get(id);
  if (!st) return;
  st.error = streamFailure(name, code, message, hostStack);
  st.done = true;
  streams.delete(id);
  if (st.waiter) { const w = st.waiter; st.waiter = null; w.reject(st.error); }
};

const collect = async (body, limit, what) => {
  const parts = [];
  let size = 0;
  for await (const part of body) {
    size += part.length;
    if (size > limit) { body.cancel(); throw new RangeError(`${what} is over ${limit} bytes; read it as a stream instead`); }
    parts.push(part);
  }
  const all = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) { all.set(part, offset); offset += part.length; }
  return all;
};
const BUFFER_LIMIT = 32 * 1024 * 1024;
const FETCH_BUFFER_LIMIT = 5 * 1024 * 1024;
const toBytes = (part) => {
  if (part instanceof Uint8Array) return part;
  if (part instanceof ArrayBuffer) return new Uint8Array(part);
  if (ArrayBuffer.isView(part)) return new Uint8Array(part.buffer, part.byteOffset, part.byteLength);
  return String(part);
};
const isIterable = (v) => v && typeof v !== 'string' && !(v instanceof Uint8Array)
    && (typeof v[Symbol.asyncIterator] === 'function' || typeof v[Symbol.iterator] === 'function');
