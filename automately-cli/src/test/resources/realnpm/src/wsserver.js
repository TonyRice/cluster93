// The ws library's server on Node's http server (D-125), on PORT (3000 as a service). The test talks to it under Node
// and through the ingress, and compares: the handshake's answer, messages both ways, a refused upgrade, and closes.
import http from 'node:http';
import { WebSocketServer } from 'ws';

const log = [];
const server = http.createServer((req, res) => res.end(`http ${req.url}`));
const wss = new WebSocketServer({
  server,
  handleProtocols: (protocols) => (protocols.has('chat') ? 'chat' : false),
  verifyClient: (info, done) => (info.req.url === '/refuse' ? done(false, 401, 'Go Away') : done(true)),
});
wss.on('connection', (ws, req) => {
  log.push(['connection', req.url, ws.protocol, req.headers['x-custom'] ?? null, typeof req.socket.remoteAddress]);
  ws.send(JSON.stringify({ hello: req.url }));
  ws.on('message', (data, isBinary) => {
    const text = isBinary ? null : String(data);
    if (text === 'close') ws.close(4002, 'done');
    else if (text === 'log') ws.send(JSON.stringify(log));
    else if (text === 'ping') ws.ping('from server');
    else ws.send(data, { binary: isBinary });
  });
  ws.on('ping', (data) => log.push(['ping', String(data)]));
  ws.on('pong', (data) => log.push(['pong', String(data)]));
  ws.on('close', (code, reason) => log.push(['close', code, String(reason)]));
});
server.listen(Number(process.env.PORT ?? 3000));
