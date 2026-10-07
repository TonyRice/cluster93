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

// Called by the event loop when timer `id` is due.
const fire = (id) => {
  const timer = timers.get(id);
  if (!timer) return;
  if (timer.repeat) host.timer(id, timer.ms); else timers.delete(id);
  try {
    const result = timer.fn(...timer.args);
    if (result && typeof result.then === 'function') {
      result.then(undefined, (e) => host.uncaught(`Uncaught error in timer callback: ${errorText(e)}`));
    }
  } catch (e) {
    host.uncaught(`Uncaught error in timer callback: ${errorText(e)}`);
  }
};
