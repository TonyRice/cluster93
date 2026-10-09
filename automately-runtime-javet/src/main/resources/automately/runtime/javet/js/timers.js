// setTimeout and setInterval: the callbacks stay here, the schedule is the isolate's event loop (EventLoop).
const timers = new Map();
let nextTimerId = 1;
const schedule = (fn, delay, args, repeat) => {
  if (typeof fn !== 'function') throw new TypeError('A timer callback must be a function');
  if (timers.size >= 10000) throw new RangeError('Too many pending timers (limit 10000)');
  const id = nextTimerId++;
  const ms = Math.max(1, Number(delay) || 0);
  timers.set(id, { fn, args, repeat, ms });
  host.timer(id, ms);
  return id;
};
const clear = (id) => { if (timers.delete(id)) host.clearTimer(id); };
define('setTimeout', (fn, delay, ...args) => schedule(fn, delay, args, false));
define('setInterval', (fn, delay, ...args) => schedule(fn, delay, args, true));
define('clearTimeout', clear);
define('clearInterval', clear);

// A throw in a callback the script didn't call itself (a microtask, process.nextTick, an http event) ends the job, as
// an uncaught exception ends Node, rather than vanishing into a promise (D-109). Node modules find this under a symbol;
// a script that calls it only fails its own job.
const reportUncaught = (e, where) => host.uncaught(`Uncaught error in ${where}: ${errorText(e)}`);
Object.defineProperty(globalThis, Symbol.for('automately.uncaught'), { value: reportUncaught });

// Promises rejected with no handler (D-110). V8 reports each as it's rejected, and again if a handler is added later;
// the event loop checks after every turn, once microtasks have run, as Node does. Each still unhandled goes to
// process.on('unhandledRejection') listeners if there are any, and otherwise ends the job, as Node's default (`throw`
// since v15) ends the process. One handled after its listeners heard of it emits 'rejectionHandled'.
const unhandled = new Map();
const reported = new WeakSet();
const processOf = () => globalThis[Symbol.for('automately.process')];
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
