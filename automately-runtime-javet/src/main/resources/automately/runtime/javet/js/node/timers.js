// A stand-in for Node's `timers` (D-103): the isolate's own timers, plus setImmediate as a zero-delay timer.
import * as promises from 'node:timers/promises';

export const setTimeout = (...args) => globalThis.setTimeout(...args);
export const clearTimeout = (id) => globalThis.clearTimeout(id);
export const setInterval = (...args) => globalThis.setInterval(...args);
export const clearInterval = (id) => globalThis.clearInterval(id);
export const setImmediate = (fn, ...args) => globalThis.setTimeout(fn, 0, ...args);
export const clearImmediate = (id) => globalThis.clearTimeout(id);
export { promises };
export default { setTimeout, clearTimeout, setInterval, clearInterval, setImmediate, clearImmediate, promises };
