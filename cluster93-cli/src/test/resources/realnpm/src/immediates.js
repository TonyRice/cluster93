// setImmediate as Node's check phase (D-121): the orderings Node guarantees from an I/O callback. Runs the same under Node.
import fs from 'node:fs';
export const run = () => new Promise((resolve) => {
  const log = [];
  // From an I/O callback, Node guarantees: nextTick, microtasks, then the check phase (immediates) before timers.
  // Any I/O callback will do: a file that isn't there answers with an error, in Node and in the isolate.
  fs.readFile('/cluster93-no-such-file', () => {
    setImmediate(() => {
      log.push('immediate 1');
      process.nextTick(() => log.push('tick inside immediate 1'));
      Promise.resolve().then(() => log.push('microtask inside immediate 1'));
      setImmediate(() => log.push('immediate queued during the check phase'));
    });
    const cleared = setImmediate(() => log.push('cleared, never runs'));
    setImmediate((a, b) => log.push(`immediate 2 with ${a} ${b}`), 'x', 'y');
    clearImmediate(cleared);
    process.nextTick(() => log.push('tick'));
    Promise.resolve().then(() => log.push('microtask'));
    const handle = setImmediate(() => {});
    log.push(typeof handle, handle.hasRef(), handle.unref() === handle, handle.constructor.name);
    setTimeout(() => resolve(log), 30);
  });
});
