// What the GraalJS adapter needs from JavaScript itself (D-126). Evaluated in each context before anything else runs,
// so the functions captured here (JSON, String, Promise…) are the built-ins, whatever a script later replaces.
(() => {
  // GraalJS's own Java interop global: powerless here (no host class lookup), and neither Node nor V8 has it.
  delete globalThis.Java;
  const { parse, stringify } = JSON;
  const isView = ArrayBuffer.isView;
  const DataViewClass = DataView;
  const Uint8 = Uint8Array;
  const PromiseClass = Promise;
  const then = Promise.prototype.then;
  const call = (fn, self, ...args) => Reflect.apply(fn, self, args);
  return Object.freeze({
    undefined: undefined,
    promise: () => {
      let resolve;
      let reject;
      const promise = new PromiseClass((yes, no) => { resolve = yes; reject = no; });
      return [promise, resolve, reject];
    },
    // As V8's own errors for these names, with Node's code (D-025).
    error: (name, code, message) => {
      const Kind = name === 'TypeError' ? TypeError : name === 'RangeError' ? RangeError : Error;
      const error = new Kind(message);
      if (Kind === Error && name !== 'Error') error.name = name;
      error.code = code;
      return error;
    },
    parse: (text) => parse(text),
    stringify: (value) => stringify(value),
    // A typed array's buffer, offset and length; null for anything else (a DataView isn't one).
    view: (value) => (isView(value) && !(value instanceof DataViewClass) ? [value.buffer, value.byteOffset, value.byteLength] : null),
    // Bytes from the host, copied into an ArrayBuffer of the script's own: from a host buffer in-process, and from a
    // Latin-1 string across an isolate's boundary, which a string crosses in one piece but a buffer byte by byte (50x).
    bytes: (hostBuffer) => call(Uint8.prototype.slice, new Uint8(hostBuffer)),
    bytesFromLatin1: (text) => {
      const n = text.length;
      const out = new Uint8(n);
      for (let i = 0; i < n; i++) out[i] = text.charCodeAt(i);
      return out;
    },
    truthy: (value) => !!value,
    string: (value) => String(value),
    isBigInt: (value) => typeof value === 'bigint',
    // A promise's state, for the run's loop to read: {state, value}.
    watch: (promise) => {
      const seen = { state: 'pending', value: undefined };
      call(then, promise, (value) => { seen.state = 'fulfilled'; seen.value = value; },
        (error) => { seen.state = 'rejected'; seen.value = error; });
      return seen;
    },
    load: (path) => import(path),
    stack: (error) => (error && typeof error === 'object' && error.stack ? String(error.stack) : String(error)),
    // Unhandled rejections (D-110), through GraalJS's own handler; the Graal global goes once it's taken.
    trackRejections: (rejected) => {
      const set = globalThis.Graal && globalThis.Graal.setUnhandledPromiseRejectionHandler;
      delete globalThis.Graal;
      if (set) set((reason, promise) => rejected(promise, reason));
      return set ?? null;
    },
  });
})()
