// A stand-in for Node's `process` (D-093): enough for npm code that checks its environment or defers work. It doesn't
// claim to be Node: `process.versions.node` is undefined, so libraries that look for it take their non-Node path.
// There's no real environment: `env` holds only NODE_ENV, and nothing of the node's or the tenant's leaks into it.

const listeners = new Map();

const process = {
  env: { NODE_ENV: 'production' },
  argv: [],
  execArgv: [],
  platform: 'linux',
  arch: 'x64',
  version: '',
  versions: {},
  release: { name: 'automately' },
  pid: 1,
  ppid: 0,
  title: 'automately',
  browser: false,
  exitCode: undefined,
  cwd: () => '/',
  chdir: () => {
    throw new Error('process.chdir() is not supported in the isolate');
  },
  umask: () => 0o022,
  uptime: () => (Date.now() - started) / 1000,
  hrtime: Object.assign((previous) => {
    const now = performanceNow();
    const seconds = Math.floor(now / 1e3);
    const nanos = Math.floor((now % 1e3) * 1e6);
    if (!previous) {
      return [seconds, nanos];
    }
    let s = seconds - previous[0];
    let n = nanos - previous[1];
    if (n < 0) {
      s -= 1;
      n += 1e9;
    }
    return [s, n];
  }, { bigint: () => BigInt(Math.floor(performanceNow() * 1e6)) }),
  memoryUsage: () => ({ rss: 0, heapTotal: 0, heapUsed: 0, external: 0, arrayBuffers: 0 }),
  nextTick: (fn, ...args) => {
    Promise.resolve().then(() => fn(...args));
  },
  emitWarning: (warning) => console.warn(typeof warning === 'string' ? 'Warning: ' + warning : String(warning)),
  exit: (code) => {
    throw new Error('process.exit(' + (code ?? '') + ') is not supported in the isolate; return from the script instead');
  },
  abort: () => {
    throw new Error('process.abort() is not supported in the isolate');
  },
  stdout: { write: (chunk) => { console.log(String(chunk).replace(/\n$/, '')); return true; }, isTTY: false },
  stderr: { write: (chunk) => { console.error(String(chunk).replace(/\n$/, '')); return true; }, isTTY: false },
  on(name, fn) { (listeners.get(name) ?? listeners.set(name, []).get(name)).push(fn); return process; },
  once(name, fn) { return process.on(name, fn); },
  off(name, fn) { listeners.set(name, (listeners.get(name) ?? []).filter((l) => l !== fn)); return process; },
  removeListener(name, fn) { return process.off(name, fn); },
  removeAllListeners(name) { if (name === undefined) { listeners.clear(); } else { listeners.delete(name); } return process; },
  emit(name, ...args) { const all = listeners.get(name) ?? []; all.forEach((l) => l(...args)); return all.length > 0; },
  listeners(name) { return (listeners.get(name) ?? []).slice(); },
  listenerCount(name) { return (listeners.get(name) ?? []).length; },
  binding: () => {
    throw new Error('process.binding() is not supported in the isolate');
  },
};

const started = Date.now();
function performanceNow() {
  return Date.now() - started;
}

export const { env, argv, platform, arch, version, versions, cwd, hrtime, nextTick, emitWarning, exit, stdout, stderr } = process;
export default process;
