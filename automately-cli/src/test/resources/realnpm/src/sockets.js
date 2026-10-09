// node:net and node:tls sockets (D-116), against the test's servers (TEST_TCP, TEST_TLS, TEST_STARTTLS, TEST_HALFCLOSE,
// with TEST_CA for their certificate). Runs the same under Node.
import net from 'node:net';
import tls from 'node:tls';

const port = (name) => Number(process.env[name]);
const ca = process.env.TEST_CA;

const error = (e) => ({ name: e.name, code: e.code, message: e.message, errno: e.errno, syscall: e.syscall, address: e.address,
  port: e.port, hostname: e.hostname });

/** One conversation with the echo server: events in order, what came back, and the socket's state along the way. */
const echo = () => new Promise((resolve) => {
  const events = [];
  const socket = net.connect(port('TEST_TCP'), '127.0.0.1', () => events.push('callback'));
  events.push('connecting ' + socket.connecting + ' ' + socket.readyState + ' ' + socket.pending);
  socket.setEncoding('utf8');
  for (const e of ['lookup', 'connect', 'ready', 'end', 'finish']) socket.on(e, () => events.push(e));
  let text = '';
  socket.on('data', (chunk) => {
    text += chunk;
    if (text === 'hello there') {
      events.push('state ' + socket.readyState + ' ' + socket.pending);
      socket.end();
    }
  });
  socket.on('close', (hadError) => resolve({
    events, text, hadError, remote: [socket.remoteAddress, socket.remotePort === port('TEST_TCP'), socket.remoteFamily],
    local: [socket.localAddress, typeof socket.localPort, socket.address().family], written: socket.bytesWritten, read: socket.bytesRead,
    state: socket.readyState, destroyed: socket.destroyed,
  }));
  // Written before the connection exists: Node holds it until then.
  socket.write('hello ');
  socket.write(Buffer.from('there'));
});

const failure = (connect) => new Promise((resolve) => {
  try {
    const socket = connect();
    socket.on('error', (e) => resolve(error(e)));
    socket.on('connect', () => { resolve('connected'); socket.destroy(); });
  } catch (e) {
    resolve(error(e));
  }
});

const secure = (options) => new Promise((resolve) => {
  const socket = tls.connect({ port: port('TEST_TLS'), host: 'localhost', ...options }, () => socket.write('x'));
  const events = [];
  for (const e of ['connect', 'secureConnect']) socket.on(e, () => events.push(e));
  socket.setEncoding('utf8');
  socket.on('data', (text) => {
    const cert = socket.getPeerCertificate(true);
    resolve({
      events, text, authorized: socket.authorized, authorizationError: socket.authorizationError, encrypted: socket.encrypted,
      protocol: socket.getProtocol(), cipher: socket.getCipher()?.name, servername: socket.servername, alpn: socket.alpnProtocol,
      subject: cert.subject, selfIssued: cert.issuerCertificate === cert, fields: Object.keys(cert).filter((k) => k !== 'issuerCertificate').sort(),
    });
    socket.destroy();
  });
  socket.on('error', (e) => resolve(error(e)));
});

export async function run() {
  const out = {};
  out.echo = await echo();

  out.halfClose = await new Promise((resolve) => {
    const socket = net.connect({ port: port('TEST_HALFCLOSE'), host: '127.0.0.1' });
    socket.end('question');
    let text = '';
    const events = [];
    socket.on('finish', () => events.push('finish'));
    socket.on('data', (chunk) => { text += chunk; });
    socket.on('end', () => events.push('end'));
    socket.on('close', () => resolve({ text, events }));
  });

  out.iterated = await (async () => {
    const socket = net.connect(port('TEST_TCP'), '127.0.0.1');
    socket.write('async iteration');
    let text = '';
    for await (const chunk of socket) {
      text += chunk;
      if (text.length >= 'async iteration'.length) break;
    }
    return [text, socket.destroyed];
  })();

  out.idle = await new Promise((resolve) => {
    const socket = net.connect(port('TEST_TCP'), '127.0.0.1');
    socket.setTimeout(50, () => { resolve(['timeout', socket.destroyed]); socket.destroy(); });
  });

  out.refused = await failure(() => net.connect(9, '127.0.0.1'));
  out.unknownHost = await failure(() => net.connect(80, 'nonexistent.invalid'));
  out.badPorts = [70000, '70000', -1, 1.5].map((p) => { try { net.connect(p, '127.0.0.1'); return 'ran'; } catch (e) { return [e.name, e.code, e.message]; } });
  out.unconnected = (() => { const s = new net.Socket(); return [s.readyState, s.connecting, s.pending, s.remoteAddress ?? null, s.bytesRead]; })();

  out.tlsTrusted = await secure({ ca });
  out.tlsRefused = await secure({});
  out.tlsUnverified = await secure({ rejectUnauthorized: false });
  out.tlsWrongName = await secure({ ca, host: '127.0.0.1' });
  out.tlsWrongNameUnverified = await secure({ ca, host: '127.0.0.1', rejectUnauthorized: false });
  out.tlsOwnCheck = await secure({ ca, servername: 'other.example', checkServerIdentity: () => undefined });
  out.tlsOwnCheckRefuses = await secure({ ca, checkServerIdentity: (host) => Object.assign(new Error('not ' + host), { code: 'MINE' }) });

  out.starttls = await new Promise((resolve) => {
    const plain = net.connect(port('TEST_STARTTLS'), '127.0.0.1');
    const seen = [];
    plain.once('data', (first) => {
      seen.push(String(first));
      plain.write('STARTTLS');
      plain.once('data', (ok) => {
        seen.push(String(ok));
        const upgraded = tls.connect({ socket: plain, ca, servername: 'localhost' }, () => {
          seen.push('secureConnect ' + upgraded.authorized + ' ' + upgraded.encrypted);
          upgraded.write('after');
        });
        upgraded.on('data', (data) => { seen.push(String(data)); upgraded.destroy(); resolve(seen); });
        upgraded.on('error', (e) => resolve([...seen, e.code]));
      });
    });
    plain.write('before');
  });

  out.identity = [
    ['a.example.com', { subjectaltname: 'DNS:*.example.com', subject: {} }],
    ['a.b.example.com', { subjectaltname: 'DNS:*.example.com', subject: {} }],
    ['example.com', { subject: { CN: 'example.com' } }],
    ['other.com', { subjectaltname: 'DNS:example.com', subject: { CN: 'other.com' } }],
    ['10.0.0.1', { subjectaltname: 'IP Address:10.0.0.1', subject: {} }],
    ['::1', { subjectaltname: 'IP Address:0:0:0:0:0:0:0:1', subject: {} }],
    ['xn--bcher-kva.example', { subjectaltname: 'DNS:*.example', subject: {} }],
  ].map(([host, cert]) => { const e = tls.checkServerIdentity(host, cert); return e ? [e.code, e.message] : 'ok'; });

  out.roots = Array.isArray(tls.rootCertificates) && tls.rootCertificates.length > 100 && tls.rootCertificates[0].startsWith('-----BEGIN CERTIFICATE-----');
  return out;
}
