// A stand-in for Node's `tty` (D-109): a job has no terminal, so isatty() is false, as for Node with its output piped.
// debug and chalk-like libraries check it to decide on colours.
import { EventEmitter } from 'node:events';

export function isatty() { return false; }

function unsupported(what) {
  const err = new Error(`${what} isn't available in the isolate: a job has no terminal`);
  err.code = 'ERR_NOT_SUPPORTED';
  return err;
}
export class ReadStream extends EventEmitter {
  constructor() { super(); throw unsupported('tty.ReadStream'); }
}
export class WriteStream extends EventEmitter {
  constructor() { super(); throw unsupported('tty.WriteStream'); }
}

export default { isatty, ReadStream, WriteStream };
