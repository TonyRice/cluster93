// Our polyfills: EventEmitter with several listeners (node:events).
import { EventEmitter } from 'node:events';
const emitter = new EventEmitter();
let count = 0;
function setup() {
  for (let k = 0; k < 5; k++) emitter.on('tick', (value) => { count += value; });
  emitter.once('never', () => {});
}
function iteration() {
  for (let k = 0; k < 1000000; k++) emitter.emit('tick', 1);
  return count;
}
