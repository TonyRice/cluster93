// zlib: no global (Node has none), only the host calls node:zlib (js/node/zlib.js) finds under a symbol (D-103).
const zlibHost = host.zlib;
const zlibCall = (result) => {
  if (result instanceof Error) throw result;
  return result;
};
Object.defineProperty(globalThis, Symbol.for('automately.zlib'), {
  value: Object.freeze({
    process: (kind, data, options) => zlibCall(zlibHost.process(kind, data, options)),
    open: (kind, options) => zlibCall(zlibHost.open(kind, options)),
    write: (handle, data) => zlibCall(zlibHost.write(handle, data)),
    flush: (handle, kind) => zlibCall(zlibHost.flush(handle, kind)),
    finish: (handle) => zlibCall(zlibHost.finish(handle)),
    close: (handle) => zlibCall(zlibHost.close(handle)),
  }),
});
