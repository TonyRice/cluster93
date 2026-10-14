// A stand-in for Node's `child_process` (D-116): its names, so libraries that import it for an optional path load
// (nodemailer's sendmail transport, mongodb's encryption helper). The isolate starts no processes: every function
// throws ERR_NOT_SUPPORTED. Code that runs programs needs the Node runtime (D-084).
import { EventEmitter } from 'node:events';

function unsupported(what) {
  const err = new Error(`${what} isn't available in the isolate: scripts don't start processes; run code that does with "runtime": "node"`);
  err.code = 'ERR_NOT_SUPPORTED';
  return err;
}

export class ChildProcess extends EventEmitter {
  constructor() { super(); throw unsupported('child_process.ChildProcess'); }
}
export function exec() { throw unsupported('child_process.exec'); }
export function execFile() { throw unsupported('child_process.execFile'); }
export function execFileSync() { throw unsupported('child_process.execFileSync'); }
export function execSync() { throw unsupported('child_process.execSync'); }
export function fork() { throw unsupported('child_process.fork'); }
export function spawn() { throw unsupported('child_process.spawn'); }
export function spawnSync() { throw unsupported('child_process.spawnSync'); }

export default { ChildProcess, exec, execFile, execFileSync, execSync, fork, spawn, spawnSync };
