// The global WebSocket, as Node's (undici's) (D-125), against the test's WebSocket server (TEST_WS): it first sends back
// the handshake headers it received, then echoes; "ping-me" makes it ping, "close-me" makes it close with 4001, and
// /refuse is refused with a 403. Runs the same under Node.
const error = (fn) => { try { fn(); return 'no error'; } catch (e) { return [e.name, e.code ?? null, e.message]; } };
const describeEvent = async (event) => {
  if (event.type === 'message') {
    const { data } = event;
    const shown = typeof data === 'string' ? (data.length > 5000 ? `${data.length} chars` : data)
      : data instanceof ArrayBuffer ? `ArrayBuffer ${Buffer.from(data).toString('hex')}`
        : `${data.constructor.name} ${data.size} ${(await data.text()).slice(0, 20)}`;
    return ['message', event.constructor.name, event.origin, shown];
  }
  if (event.type === 'close') return ['close', event.constructor.name, event.code, event.reason, event.wasClean];
  if (event.type === 'error') return ['error', event.constructor.name, event.message, String(event.error)];
  return [event.type, event.constructor.name];
};

/** A connection's events in order, and a way to wait for the next one. */
function open(url, protocols) {
  const ws = new WebSocket(url, protocols);
  const events = [];
  const queue = [];
  let waiting = null;
  for (const type of ['open', 'message', 'error', 'close']) {
    ws.addEventListener(type, (event) => {
      const item = describeEvent(event);
      events.push(item);
      if (waiting) { const w = waiting; waiting = null; w(item); } else queue.push(item);
    });
  }
  const next = () => (queue.length ? queue.shift() : new Promise((resolve) => { waiting = resolve; }));
  return { ws, events, next };
}

export async function run() {
  const url = process.env.TEST_WS;
  const out = {};
  out.basics = [typeof WebSocket, typeof CloseEvent, typeof ErrorEvent, WebSocket.CONNECTING, WebSocket.OPEN, WebSocket.CLOSING,
    WebSocket.CLOSED, WebSocket.prototype.OPEN, Object.prototype.toString.call(new CloseEvent('close', { code: 4000 })),
    new CloseEvent('x').code, new CloseEvent('x', { code: 1001, reason: 'r', wasClean: true }).reason,
    new ErrorEvent('e', { message: 'm' }).message, new ErrorEvent('e').lineno];
  out.constructorErrors = [error(() => new WebSocket('ftp://x')), error(() => new WebSocket('ws://x/#f')),
    error(() => new WebSocket('ws://x', ['a', 'A'])), error(() => new WebSocket('ws://x', ['bad protocol'])),
    error(() => new WebSocket('not a url')), error(() => new WebSocket())];

  const c = open(`${url.replace('ws:', 'http:')}/global?x=1`, { protocols: ['chat', 'other'], headers: { 'X-Custom': 'c' } });
  out.connecting = [c.ws.readyState, c.ws.url, c.ws.protocol, c.ws.extensions, c.ws.binaryType, c.ws.bufferedAmount,
    error(() => c.ws.send('early')), error(() => c.ws.close(1001)), error(() => c.ws.close(1000, 'x'.repeat(124)))];
  out.opened = await c.next();
  out.state = [c.ws.readyState, c.ws.protocol, c.ws.extensions];
  const handshake = await c.next();
  out.handshake = handshake[0] === 'message' ? JSON.parse(handshake[3]) : handshake;
  if (handshake[0] !== 'message') return out;
  c.ws.send('hello');
  out.text = await c.next();
  c.ws.binaryType = 'arraybuffer';
  c.ws.send(new Uint8Array([1, 2, 3, 255]));
  out.binary = await c.next();
  c.ws.binaryType = 'nonsense';
  out.binaryType = c.ws.binaryType;
  c.ws.send(new Blob(['from a blob']));
  out.blob = await c.next();
  c.ws.send('x'.repeat(300000));
  out.buffered = c.ws.bufferedAmount;
  out.big = await c.next();
  c.ws.send(new ArrayBuffer(70000));
  out.bigBinary = await c.next();
  c.ws.send('ping-me');
  out.afterPing = await c.next();
  c.ws.send('close-me');
  out.closed = await c.next();
  out.after = [c.ws.readyState, c.ws.bufferedAmount, error(() => c.ws.send('late'))];

  // Closed from this side.
  const d = open(`${url}/second`);
  await d.next();
  await d.next();
  d.ws.close(1000, 'client done');
  out.closing = d.ws.readyState;
  out.clientClose = await d.next();

  // Closed while connecting, refused, and nothing listening.
  const early = open(`${url}/early`);
  early.ws.close();
  out.closedEarly = [early.ws.readyState, await early.next(), await early.next()];
  const refused = open(`${url}/refuse`);
  out.refused = [await refused.next(), await refused.next()];
  const nobody = open('ws://127.0.0.1:9/');
  out.nobody = [await nobody.next(), await nobody.next()];
  out.events = c.events.length;
  return out;
}
