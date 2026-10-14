// setTimeout and setInterval: the callbacks stay here, the schedule is the isolate's event loop (EventLoop).
const timers = new Map();
let nextTimerId = 1;
const callbackError = (fn) => {
  const received = fn === null ? 'null' : fn === undefined ? 'undefined' : typeof fn === 'object' ? `an instance of ${fn.constructor?.name ?? 'Object'}`
    : `type ${typeof fn} (${typeof fn === 'string' ? `'${fn}'` : String(fn)})`;
  const err = new TypeError(`The "callback" argument must be of type function. Received ${received}`);
  err.code = 'ERR_INVALID_ARG_TYPE';
  return err;
};
// Node's delay: a number from 1 to 2^31-1 ms, else 1 (and a TimeoutOverflowWarning past the top).
const delayOf = (delay) => {
  let ms = Number(delay);
  if (ms > 2147483647) {
    globalThis[Symbol.for('cluster93.process')]?.emitWarning(`${delay} does not fit into a 32-bit signed integer.\nTimeout duration was set to 1.`,
      'TimeoutOverflowWarning');
    ms = 1;
  }
  return ms >= 1 && ms <= 2147483647 ? ms : 1;
};
const schedule = (fn, delay, args, repeat) => {
  if (typeof fn !== 'function') throw callbackError(fn);
  if (timers.size >= 10000) throw new RangeError('Too many pending timers (limit 10000)');
  const id = nextTimerId++;
  const ms = delayOf(delay);
  timers.set(id, { fn, args, repeat, ms });
  host.timer(id, ms);
  return id;
};

// Node's Timeout (D-123): setTimeout and setInterval return one. Its primitive is the timer's id, so +timeout and
// clearTimeout(id) work as in Node. ref and unref are kept but change nothing: a run lasts until its promise settles.
class Timeout {
  constructor(id, ms, fn, repeat) {
    this._id = id;
    this._idleTimeout = ms;
    this._onTimeout = fn;
    this._repeat = repeat ? ms : null;
    this._ref = true;
  }
  ref() { this._ref = true; return this; }
  unref() { this._ref = false; return this; }
  hasRef() { return this._ref; }
  refresh() {
    if (timers.has(this._id)) { host.clearTimer(this._id); host.timer(this._id, this._idleTimeout); }
    return this;
  }
  close() { clear(this); return this; }
  [Symbol.toPrimitive]() { return this._id; }
  [Symbol.dispose]() { clear(this); }
}
const timeout = (fn, delay, args, repeat) => {
  const id = schedule(fn, delay, args, repeat);
  return new Timeout(id, timers.get(id).ms, fn, repeat);
};
const clear = (timer) => {
  const id = timer instanceof Timeout ? timer._id : Number(timer);
  if (timers.delete(id)) host.clearTimer(id);
};
define('setTimeout', (fn, delay, ...args) => timeout(fn, delay, args, false));
define('setInterval', (fn, delay, ...args) => timeout(fn, delay, args, true));
define('clearTimeout', clear);
define('clearInterval', clear);

// The isolate's own: runs `fn` among the I/O callbacks of the event loop's next turn, as Node handles a network read
// that comes after this one (D-121).
Object.defineProperty(globalThis, Symbol.for('cluster93.nextTurn'), {
  value: (fn) => {
    const id = nextTimerId++;
    timers.set(id, { fn, args: [], repeat: false, ms: 0 });
    host.nextTurn(id);
  },
});

// setImmediate, as Node's check phase (D-121): after each turn's I/O callbacks, the event loop runs the immediates
// queued before the phase began, one at a time with microtasks after each; those queued meanwhile wait for the next
// turn. Not a zero-delay timer, which waits a millisecond and runs with the timers.
class Immediate {
  constructor(fn, args) { this._onImmediate = fn; this._args = args; this._cleared = false; }
  ref() { return this; }
  unref() { return this; }
  hasRef() { return true; }
}
const immediates = [];
let immediateBatch = 0;
define('setImmediate', (fn, ...args) => {
  if (typeof fn !== 'function') {
    const err = new TypeError(`The "callback" argument must be of type function. Received ${fn === null ? 'null' : typeof fn}`);
    err.code = 'ERR_INVALID_ARG_TYPE';
    throw err;
  }
  if (immediates.length >= 10000) throw new RangeError('Too many pending immediates (limit 10000)');
  const immediate = new Immediate(fn, args);
  if (immediates.length === 0) host.immediates(true);
  immediates.push(immediate);
  return immediate;
});
define('clearImmediate', (immediate) => { if (immediate instanceof Immediate) immediate._cleared = true; });
// Called by the event loop: how many immediates this check phase runs, then each in turn.
const startImmediates = () => (immediateBatch = immediates.length);
const runImmediate = () => {
  if (immediateBatch <= 0) return;
  immediateBatch--;
  const immediate = immediates.shift();
  if (immediates.length === 0) host.immediates(false);
  if (immediate._cleared) return;
  immediate._cleared = true;
  try {
    immediate._onImmediate(...immediate._args);
  } catch (e) {
    host.uncaught(`Uncaught error in setImmediate callback: ${errorText(e)}`);
  }
};

// A throw in a callback the script didn't call itself (a microtask, process.nextTick, an http event) ends the job, as
// an uncaught exception ends Node, rather than vanishing into a promise (D-109). Node modules find this under a symbol;
// a script that calls it only fails its own job.
const reportUncaught = (e, where) => host.uncaught(`Uncaught error in ${where}: ${errorText(e)}`);
Object.defineProperty(globalThis, Symbol.for('cluster93.uncaught'), { value: reportUncaught });

// Promises rejected with no handler (D-110). V8 reports each as it's rejected, and again if a handler is added later;
// the event loop checks after every turn, once microtasks have run, as Node does. Each still unhandled goes to
// process.on('unhandledRejection') listeners if there are any, and otherwise ends the job, as Node's default (`throw`
// since v15) ends the process. One handled after its listeners heard of it emits 'rejectionHandled'.
const unhandled = new Map();
const reported = new WeakSet();
const processOf = () => globalThis[Symbol.for('cluster93.process')];
const rejected = (promise, reason) => { unhandled.set(promise, reason); };
const handled = (promise) => {
  if (unhandled.delete(promise) || !reported.has(promise)) return;
  reported.delete(promise);
  try { processOf()?.emit('rejectionHandled', promise); } catch (e) { reportUncaught(e, "a 'rejectionHandled' listener"); }
};
const checkRejections = () => {
  for (const [promise, reason] of unhandled) {
    unhandled.delete(promise);
    try {
      if (processOf()?.emit('unhandledRejection', reason, promise)) {
        reported.add(promise);
        continue;
      }
    } catch (e) {
      reportUncaught(e, "an 'unhandledRejection' listener");
      return;
    }
    host.uncaught(`Unhandled promise rejection: ${errorText(reason)}`);
    return;
  }
};

// Called by the event loop when timer `id` is due.
const fire = (id) => {
  const timer = timers.get(id);
  if (!timer) return;
  if (timer.repeat) host.timer(id, timer.ms); else timers.delete(id);
  // A promise the callback returns is ignored, as in Node; if it rejects, that's an unhandled rejection (below).
  try {
    timer.fn(...timer.args);
  } catch (e) {
    host.uncaught(`Uncaught error in timer callback: ${errorText(e)}`);
  }
};
