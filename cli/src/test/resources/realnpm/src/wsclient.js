// The ws library's client (D-125) against the test's WebSocket server (TEST_WS), which first sends back the handshake
// headers it received, then echoes; "ping-me" makes it ping, "close-me" makes it close with 4001. Runs the same under Node.
import WebSocket from 'ws';

/** Opens a connection and gives its events in order, with a way to wait for the next message. */
function connect(url, protocols, options) {
  const ws = new WebSocket(url, protocols, options);
  const events = [];
  const messages = [];
  let waiting = null;
  ws.on('upgrade', (res) => events.push(['upgrade', res.statusCode, res.headers.upgrade, res.headers['sec-websocket-protocol'] ?? null,
    res.headers['sec-websocket-extensions'] ?? null]));
  ws.on('message', (data, isBinary) => {
    const item = [isBinary, isBinary ? Buffer.from(data).toString('hex') : String(data)];
    if (waiting) { const w = waiting; waiting = null; w(item); } else messages.push(item);
  });
  ws.on('ping', (data) => events.push(['ping', String(data)]));
  ws.on('pong', (data) => events.push(['pong', String(data)]));
  const next = () => (messages.length ? Promise.resolve(messages.shift()) : new Promise((resolve) => { waiting = resolve; }));
  const opened = new Promise((resolve, reject) => {
    ws.once('open', () => { events.push(['open', ws.protocol, ws.extensions, ws.readyState, ws.url]); resolve(); });
    ws.once('error', (e) => reject(e));
  });
  const closed = new Promise((resolve) => ws.once('close', (code, reason) => resolve([code, String(reason)])));
  return { ws, events, next, opened, closed };
}

const failure = (url, options) => new Promise((resolve) => {
  const ws = new WebSocket(url, options);
  ws.on('error', (e) => resolve([e.name, e.code ?? null, e.message.replace(/:\d+$/, ':PORT')]));
  ws.on('open', () => resolve('opened'));
});

export async function run() {
  const url = process.env.TEST_WS;
  const out = {};

  const c = connect(`${url}/chat?room=1`, ['chat', 'other'], { headers: { 'X-Custom': 'c' } });
  await c.opened;
  const [, headers] = await c.next();
  out.handshake = JSON.parse(headers);
  c.ws.send('hello');
  out.text = await c.next();
  c.ws.send(Buffer.from([1, 2, 3, 255]));
  out.binary = await c.next();
  c.ws.send('x'.repeat(300000));
  out.big = (await c.next())[1].length;
  c.ws.send(new Uint8Array(70000).fill(7), { binary: true });
  out.bigBinary = (await c.next())[1].length;
  c.ws.ping('pp');
  c.ws.send('ping-me');
  out.afterPing = await c.next();
  c.ws.send('close-me');
  out.closed = await c.closed;
  out.events = c.events;
  out.state = [c.ws.readyState, c.ws.bufferedAmount];

  // Without compression, and a close from this side.
  const plain = connect(`${url}/plain`, undefined, { perMessageDeflate: false });
  await plain.opened;
  await plain.next();
  plain.ws.close(1000, 'client done');
  out.clientClose = await plain.closed;
  out.plainEvents = plain.events;

  out.refused = await failure(`${url}/refuse`);
  out.noServer = await failure('ws://127.0.0.1:9/');
  out.badUrl = await new Promise((resolve) => { try { new WebSocket('http//nope'); resolve('no error'); } catch (e) { resolve([e.name, e.message]); } });
  return out;
}
