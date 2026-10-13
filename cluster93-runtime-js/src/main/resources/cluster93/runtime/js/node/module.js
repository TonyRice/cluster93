// A stand-in for Node's `module` (D-123): what libraries read when they load (pino's transport, Fastify's logger).
// A script's modules are bundled when it's published, so nothing loads code at run time: createRequire() gives a
// require that throws ERR_NOT_SUPPORTED.
function unsupported(what) {
  const err = new Error(`${what} isn't available in the isolate: your code is bundled when it's published, so import what you need`);
  err.code = 'ERR_NOT_SUPPORTED';
  return err;
}

export const builtinModules = Object.freeze(['_http_agent', '_http_client', '_http_common', '_http_incoming', '_http_outgoing', '_http_server',
  '_stream_duplex', '_stream_passthrough', '_stream_readable', '_stream_transform', '_stream_wrap', '_stream_writable', '_tls_common',
  '_tls_wrap', 'assert', 'assert/strict', 'async_hooks', 'buffer', 'child_process', 'cluster', 'console', 'constants', 'crypto', 'dgram',
  'diagnostics_channel', 'dns', 'dns/promises', 'domain', 'events', 'fs', 'fs/promises', 'http', 'http2', 'https', 'inspector',
  'inspector/promises', 'module', 'net', 'os', 'path', 'path/posix', 'path/win32', 'perf_hooks', 'process', 'punycode', 'querystring',
  'readline', 'readline/promises', 'repl', 'stream', 'stream/consumers', 'stream/promises', 'stream/web', 'string_decoder', 'sys',
  'timers', 'timers/promises', 'tls', 'trace_events', 'tty', 'url', 'util', 'util/types', 'v8', 'vm', 'wasi', 'worker_threads', 'zlib']);

export function isBuiltin(name) {
  const bare = String(name).startsWith('node:') ? String(name).slice(5) : String(name);
  return builtinModules.includes(bare);
}

export function createRequire() {
  return function require(id) { throw unsupported(`require('${id}')`); };
}

export function syncBuiltinESMExports() {}
export function findSourceMap() { return undefined; }
export function register() { throw unsupported('module.register()'); }
export class SourceMap {
  constructor() { throw unsupported('module.SourceMap'); }
}

export function Module() { throw unsupported('new Module()'); }
Object.assign(Module, { builtinModules, isBuiltin, createRequire, syncBuiltinESMExports, findSourceMap, register, SourceMap, Module });

export default Module;
