import EventEmitter3 from 'eventemitter3';
import { EventEmitter } from 'node:events';
import { promisify, format } from 'node:util';
import path from 'node:path';
import { Buffer } from 'node:buffer';
export const run = async () => {
  const seen = [];
  const e3 = new EventEmitter3(); e3.on('x', v => seen.push('e3:' + v)); e3.emit('x', 1);
  const node = new EventEmitter(); node.once('y', v => seen.push('node:' + v)); node.emit('y', 2);
  const wait = promisify((ms, cb) => setTimeout(() => cb(null, ms), ms));
  return { seen, waited: await wait(5), formatted: format('%s=%d', 'a', 1), joined: path.join('/a', '../b'), b64: Buffer.from('hi').toString('base64') };
};
