// A stand-in for Node's `timers` (D-103): the isolate's own timers, and its check phase for setImmediate (D-121).
import * as promises from 'node:timers/promises';

export const setTimeout = (...args) => globalThis.setTimeout(...args);
export const clearTimeout = (id) => globalThis.clearTimeout(id);
export const setInterval = (...args) => globalThis.setInterval(...args);
export const clearInterval = (id) => globalThis.clearInterval(id);
export const setImmediate = (...args) => globalThis.setImmediate(...args);
export const clearImmediate = (immediate) => globalThis.clearImmediate(immediate);
export { promises };
export default { setTimeout, clearTimeout, setInterval, clearInterval, setImmediate, clearImmediate, promises };
