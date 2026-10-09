// The WHATWG globals (D-124): Blob and File, Headers, Request, Response, FormData, fetch, the streams, the text and
// compression streams, MessageChannel and BroadcastChannel, node:stream/web and Node's fromWeb/toWeb. TEST_HTTP is the
// test's HTTP server. Runs the same under Node.
import { Readable, Writable } from 'node:stream';
import * as web from 'node:stream/web';
import { Blob as BufferBlob, File as BufferFile } from 'node:buffer';

const error = (fn) => { try { fn(); return 'no error'; } catch (e) { return [e.name, e.code ?? null, e.message]; } };
const rejection = (p) => p.then(() => 'no error', (e) => [e.name, e.code ?? null, e.message]);
const hex = (bytes) => Array.from(new Uint8Array(bytes)).map((b) => b.toString(16).padStart(2, '0')).join('');
const read = async (stream) => {
  const chunks = [];
  for await (const chunk of stream) chunks.push(typeof chunk === 'string' ? chunk : new TextDecoder().decode(chunk));
  return chunks;
};
const boundaryless = (text) => text.replace(/formdata-undici-0\d{11}/g, 'formdata-undici-0BOUNDARY');

export async function run() {
  const out = {};
  const base = process.env.TEST_HTTP;

  // --- Blob and File ---
  const blob = new Blob(['héllo ', new Uint8Array([119, 111]), new Blob(['rld'])], { type: 'Text/Plain' });
  const file = new File(['file body'], 'notes.txt', { type: 'text/plain', lastModified: 1700000000000 });
  out.blob = {
    size: blob.size, type: blob.type, text: await blob.text(), slice: await blob.slice(1, -2, 'x/y').text(), sliceType: blob.slice(0, 1, 'X/Y').type,
    bytes: hex(await blob.slice(0, 3).arrayBuffer()), stream: (await read(blob.stream())).join(''), tag: Object.prototype.toString.call(blob),
    badType: new Blob([], { type: 'café' }).type, native: await new Blob(['a\r\nb\rc'], { endings: 'native' }).text(),
    file: [file.name, file.lastModified, file.size, file.type, file instanceof Blob, Object.prototype.toString.call(file)],
    sameAsBuffer: BufferBlob === Blob && BufferFile === File,
    errors: [error(() => new Blob('nope')), error(() => new File(['x'])), error(() => new Blob([], { endings: 'weird' }))],
  };

  // --- Headers ---
  const headers = new Headers({ 'Content-Type': 'text/plain', 'X-B': '2' });
  headers.append('x-a', ' 1 ');
  headers.append('X-A', '3');
  headers.append('Set-Cookie', 'a=1');
  headers.append('set-cookie', 'b=2');
  const seen = [];
  headers.forEach((value, name) => seen.push(`${name}=${value}`));
  const copy = new Headers(headers);
  copy.set('x-a', 'only');
  copy.delete('x-b');
  out.headers = {
    entries: [...headers], seen, get: headers.get('X-A'), cookies: headers.getSetCookie(), has: [headers.has('content-type'), headers.has('nope')],
    copy: [...copy], fromPairs: [...new Headers([['a', '1'], ['A', '2']])], tag: Object.prototype.toString.call(headers),
    errors: [error(() => headers.append('bad name', 'x')), error(() => headers.set('x', 'a\nb')), error(() => new Headers([['only']])),
      error(() => headers.get('bad name'))],
  };

  // --- Response ---
  const json = Response.json({ a: 1 }, { status: 201, headers: { 'x-json': 'yes' } });
  const fromParams = new Response(new URLSearchParams({ q: 'a b', n: '1' }));
  const fromBlob = new Response(new Blob(['blob body'], { type: 'text/blob' }));
  const redirect = Response.redirect('https://example.com/next', 307);
  const cloned = new Response('twice');
  const twin = cloned.clone();
  out.response = {
    json: [json.status, json.statusText, json.ok, [...json.headers], await json.text()],
    fromParams: [[...fromParams.headers], await fromParams.text()],
    fromBlob: [[...fromBlob.headers], await fromBlob.text()],
    redirect: [redirect.status, redirect.headers.get('location'), redirect.type],
    error: (() => { const e = Response.error(); return [e.type, e.status, e.ok, e.body]; })(),
    clone: [await cloned.text(), await twin.text()],
    used: await (async () => { const r = new Response('once'); await r.text(); return [r.bodyUsed, await rejection(r.text())]; })(),
    noBody: [new Response(null, { status: 204 }).body, new Response().status],
    stream: await (async () => {
      const r = new Response(new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode('st')); c.enqueue(new TextEncoder().encode('ream')); c.close(); } }));
      return r.text();
    })(),
    formData: boundaryless(await (async () => {
      const form = new FormData();
      form.append('name', 'value');
      form.append('upload', new Blob(['data'], { type: 'text/plain' }), 'a.txt');
      return new Response(form).text();
    })()),
    readForm: await (async () => {
      const form = new FormData();
      form.append('a', '1');
      form.append('file', new File(['contents'], 'f.txt', { type: 'text/plain' }));
      const back = await new Response(form).formData();
      const f = back.get('file');
      return [back.get('a'), f.name, f.type, await f.text()];
    })(),
    errors: [error(() => new Response('x', { status: 204 })), error(() => new Response('x', { status: 600 })),
      error(() => Response.redirect('https://example.com', 200))],
  };

  // --- Request ---
  const request = new Request('HTTP://Example.COM/a/../b?x=1#frag', { method: 'post', headers: { 'X-R': '1' }, body: 'payload' });
  out.request = {
    fields: [request.method, request.url, request.redirect, request.mode, request.credentials, request.cache, request.destination,
      request.referrer, request.keepalive, request.duplex, request.signal instanceof AbortSignal],
    headers: [...request.headers], body: await request.clone().text(), custom: new Request('https://example.com', { method: 'patch' }).method,
    errors: [error(() => new Request('/relative')), error(() => new Request('https://example.com', { method: 'GET', body: 'x' })),
      error(() => new Request('https://example.com', { method: 'CONNECT' })), error(() => new Request('https://example.com', { method: 'bad method' })),
      error(() => new Request('https://user:pass@example.com')),
      error(() => new Request('https://example.com', { method: 'POST', body: new ReadableStream() }))],
  };

  // --- FormData ---
  const form = new FormData();
  form.append('a', '1');
  form.append('a', '2');
  form.append('blob', new Blob(['x']));
  form.set('b', 'set');
  out.formData = {
    entries: [...form].map(([name, value]) => [name, typeof value === 'string' ? value : [value.constructor.name, value.name]]),
    getAll: form.getAll('a'), has: form.has('blob'), deleted: (form.delete('a'), [...form.keys()]),
  };

  // --- fetch ---
  const got = await fetch(base + '/json');
  out.fetch = {
    json: [got.status, got.ok, got.type, got.redirected, got.url === base + '/json', got.headers.get('content-type'), await got.json()],
    post: await (await fetch(base + '/echo', { method: 'POST', body: 'sent', headers: { 'X-Custom': 'c' } })).json(),
    form: await (await fetch(base + '/echo', { method: 'POST', body: new URLSearchParams({ a: '1' }) })).json(),
    redirected: await (async () => { const r = await fetch(base + '/redirect'); return [r.status, r.redirected, r.url === base + '/json', await r.json()]; })(),
    manual: await (async () => { const r = await fetch(base + '/redirect', { redirect: 'manual' }); return [r.status, r.headers.get('location')]; })(),
    error: await rejection(fetch(base + '/redirect', { redirect: 'error' })),
    gzip: await (await fetch(base + '/gzip')).json(),
    big: (await (await fetch(base + '/big')).arrayBuffer()).byteLength,
    streamed: await (async () => {
      const r = await fetch(base + '/big');
      let bytes = 0;
      for await (const chunk of r.body) bytes += chunk.byteLength;
      return bytes;
    })(),
    status: await (async () => { const r = await fetch(base + '/status/404'); return [r.status, r.ok, r.statusText, await r.text()]; })(),
    aborted: await rejection(fetch(base + '/slow', { signal: AbortSignal.timeout(100) })),
    refused: await rejection(fetch('http://127.0.0.1:9/')).then((e) => [e[0], e[2]]),
    badUrl: await rejection(fetch('not a url')),
  };

  // --- streams ---
  const upper = new TransformStream({ transform(chunk, controller) { controller.enqueue(chunk.toUpperCase()); } });
  const source = new ReadableStream({ start(c) { ['a', 'b', 'c'].forEach((x) => c.enqueue(x)); c.close(); } });
  const [left, right] = source.pipeThrough(upper).tee();
  const collected = [];
  await new ReadableStream({ start(c) { c.enqueue('one'); c.enqueue('two'); c.close(); } })
    .pipeTo(new WritableStream({ write(chunk) { collected.push(chunk); } }));
  const encoded = new ReadableStream({ start(c) { c.enqueue('héllo'); c.close(); } }).pipeThrough(new TextEncoderStream());
  const roundTrips = {};
  for (const format of ['gzip', 'deflate', 'deflate-raw']) {
    const packed = new Blob(['compress me '.repeat(20)]).stream().pipeThrough(new CompressionStream(format));
    roundTrips[format] = (await read(packed.pipeThrough(new DecompressionStream(format)).pipeThrough(new TextDecoderStream()))).join('').length;
  }
  out.streams = {
    tee: [await read(left), await read(right)], piped: collected, encoded: hex(await new Response(encoded).arrayBuffer()), roundTrips,
    queuing: [new CountQueuingStrategy({ highWaterMark: 3 }).highWaterMark, new ByteLengthQueuingStrategy({ highWaterMark: 8 }).size(new Uint8Array(5))],
    reader: await (async () => {
      const reader = new ReadableStream({ start(c) { c.enqueue(1); c.enqueue(2); c.close(); } }).getReader();
      return [await reader.read(), await reader.read(), await reader.read()];
    })(),
    module: web.ReadableStream === ReadableStream && web.TextDecoderStream === TextDecoderStream,
    fromWeb: await (async () => {
      const node = Readable.fromWeb(new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode('from web')); c.close(); } }));
      const chunks = [];
      for await (const chunk of node) chunks.push(Buffer.from(chunk).toString());
      return chunks.join('');
    })(),
    toWeb: (await read(Readable.toWeb(Readable.from(['to', ' web'])))).join(''),
    writableToWeb: await (async () => {
      const got = [];
      const writer = Writable.toWeb(new Writable({ write(chunk, enc, cb) { got.push(chunk.toString()); cb(); } })).getWriter();
      await writer.write('w1');
      await writer.write('w2');
      await writer.close();
      return got;
    })(),
    errors: [error(() => new CompressionStream('zip')), error(() => new ReadableStream().getReader().releaseLock() ?? new ReadableStream({}, { highWaterMark: -1 }))],
  };

  // --- messaging ---
  out.messaging = await new Promise((resolve) => {
    const events = [];
    const { port1, port2 } = new MessageChannel();
    port2.on('message', (value) => events.push(['on', value]));
    port2.addEventListener('message', (event) => events.push(['event', event.constructor.name, event.data, event.origin]));
    port2.on('close', () => events.push('closed'));
    port1.postMessage({ n: 1, list: [1, 2] });
    port1.postMessage('second');
    events.push(['clone', error(() => port1.postMessage(() => 1))]);
    const a = new BroadcastChannel('room');
    const b = new BroadcastChannel('room');
    b.onmessage = (event) => events.push(['broadcast', event.data]);
    a.postMessage('hi room');
    setTimeout(() => {
      port1.close();
      a.close();
      b.close();
      setTimeout(() => resolve(events), 20);
    }, 30);
  });
  return out;
}
