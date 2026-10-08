// The Web and Node globals (D-106), compared with real Node: URL, atob/btoa, DOMException, Event/EventTarget,
// AbortController/AbortSignal (and node:stream using them), structuredClone, queueMicrotask, setImmediate, performance.
import { Readable, Writable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { setTimeout as sleep } from 'node:timers/promises';

const errorOf = (fn) => { try { fn(); return null; } catch (e) { return [e.name, e.constructor.name, e.code ?? null]; } };

function urls() {
  const u = new URL('HTTPS://User:Pw@Example.COM:443/a/./b/../c%20d?x=1&y=2#frag');
  const params = new URLSearchParams('a=1&b=2&a=3');
  params.append('c', 'x y');
  params.sort();
  return [u.href, u.origin, u.host, u.pathname, u.search, u.hash, u.username, new URL('../x?q', 'http://h/a/b/c').href,
    [...params], params.toString(), params.getAll('a'), URL.canParse?.('nope') ?? 'n/a', errorOf(() => new URL('nope'))?.[0]];
}

function events() {
  const seen = [];
  const target = new EventTarget();
  const listener = (e) => seen.push(['fn', e.type, e.target === target, e.defaultPrevented]);
  target.addEventListener('x', listener);
  target.addEventListener('x', listener);
  target.addEventListener('x', { handleEvent: (e) => { seen.push('object'); e.preventDefault(); } });
  target.addEventListener('x', () => seen.push('once'), { once: true });
  const event = new Event('x', { cancelable: true });
  seen.push(target.dispatchEvent(event), event.defaultPrevented, target.dispatchEvent(new Event('x')));
  const custom = new CustomEvent('c', { detail: { n: 1 } });
  seen.push(custom.detail.n, custom instanceof Event, event.isTrusted, new Event('y').cancelable);
  const stopper = new EventTarget();
  stopper.addEventListener('s', (e) => { seen.push('first'); e.stopImmediatePropagation(); });
  stopper.addEventListener('s', () => seen.push('never'));
  stopper.dispatchEvent(new Event('s'));
  return seen;
}

async function aborts() {
  const out = [];
  const controller = new AbortController();
  const { signal } = controller;
  signal.addEventListener('abort', () => out.push('listener'));
  signal.onabort = () => out.push('onabort');
  out.push(signal.aborted);
  controller.abort();
  controller.abort('again');
  out.push(signal.aborted, signal.reason.name, signal.reason instanceof DOMException, signal.reason.code, errorOf(() => signal.throwIfAborted())?.[0]);
  const given = AbortSignal.abort('why');
  out.push(given.aborted, given.reason);
  const timed = AbortSignal.timeout(5);
  await sleep(30);
  out.push(timed.aborted, timed.reason.name);
  const parent = new AbortController();
  const any = AbortSignal.any([new AbortController().signal, parent.signal]);
  parent.abort('first');
  out.push(any.aborted, any.reason, signal instanceof AbortSignal, errorOf(() => new AbortSignal())?.[0]);
  // node:stream with a real signal: aborting stops the pipeline with an AbortError.
  const streaming = new AbortController();
  const endless = new Readable({ read() { this.push('x'); } });
  const sink = new Writable({ write(chunk, enc, done) { setTimeout(done, 1); } });
  setTimeout(() => streaming.abort(), 10);
  out.push(await pipeline(endless, sink, { signal: streaming.signal }).then(() => 'finished', (e) => [e.name, e.code]));
  out.push(await sleep(1000, 'late', { signal: AbortSignal.abort() }).catch((e) => e.name));
  return out;
}

function clones() {
  const cycle = { name: 'c' };
  cycle.self = cycle;
  const original = { date: new Date(5), re: /a/gi, map: new Map([[1, { x: 1 }]]), set: new Set([1, 'a']), bytes: new Uint8Array([1, 2, 3]),
    nested: [1, [2, { three: 3 }]], error: new RangeError('bad'), big: 10n, boxed: Object('s'), cycle, undef: undefined, nan: NaN };
  class Point { constructor() { this.x = 1; } get y() { return 2; } }
  const copy = structuredClone(original);
  return [copy !== original, copy.date instanceof Date && copy.date.getTime(), copy.re.source + '/' + copy.re.flags, copy.map.get(1).x,
    copy.map.get(1) !== original.map.get(1), [...copy.set], [...copy.bytes], copy.bytes.buffer !== original.bytes.buffer,
    JSON.stringify(copy.nested), copy.error instanceof RangeError, copy.error.message, copy.big === 10n, typeof copy.boxed, copy.boxed.valueOf(),
    copy.cycle.self === copy.cycle, 'undef' in copy, Number.isNaN(copy.nan), Object.getPrototypeOf(structuredClone(new Point())) === Object.prototype,
    structuredClone(new Point()).y, errorOf(() => structuredClone({ f() {} })), errorOf(() => structuredClone(Symbol('s'))), structuredClone(undefined),
    structuredClone([1, , 3]).length]; // eslint-disable-line no-sparse-arrays
}

function encodings() {
  return [btoa('hello'), atob('aGVsbG8='), atob(' aGVs bG8 '), btoa(String.fromCharCode(255, 0, 128)), errorOf(() => btoa('€')),
    errorOf(() => atob('a')), atob('aGk'), new DOMException('m', 'NotFoundError').code, DOMException.ABORT_ERR,
    new DOMException('m').name, new DOMException('m', { name: 'DataError', cause: 1 }).cause];
}

async function scheduling() {
  const order = [];
  setTimeout(() => order.push('timeout'), 0);
  setImmediate(() => order.push('immediate'));
  queueMicrotask(() => order.push('microtask'));
  Promise.resolve().then(() => order.push('promise'));
  order.push('sync');
  await sleep(20);
  const start = performance.now();
  await sleep(15);
  const elapsed = performance.now() - start;
  return [order.slice(0, 3), order.length, typeof performance.timeOrigin, elapsed >= 10, errorOf(() => queueMicrotask('x'))?.[0],
    typeof clearImmediate];
}

export const run = async () => ({ urls: urls(), events: events(), aborts: await aborts(), clones: clones(), encodings: encodings(),
  scheduling: await scheduling() });
