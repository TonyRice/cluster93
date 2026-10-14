// node:http and node:https as clients (D-109), against the test's local server (TEST_HTTP). Runs the same under Node.
import http from 'node:http';
import https from 'node:https';
import { request, get, STATUS_CODES, METHODS, validateHeaderName } from 'node:http';

const base = process.env.TEST_HTTP;

/** One request: its status, chosen headers and body, or its error's code. */
function call(path, options = {}, body, after) {
  return new Promise((resolve) => {
    const events = [];
    const req = request(base + path, options, (res) => {
      events.push('response');
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        events.push('end');
        resolve({
          status: res.statusCode,
          message: res.statusMessage,
          version: res.httpVersion,
          type: res.headers['content-type'],
          cookies: res.headers['set-cookie'],
          repeat: res.headers['x-repeat'],
          rawRepeat: res.rawHeaders.filter((_, i, all) => i % 2 === 1 && all[i - 1].toLowerCase() === 'x-repeat'),
          distinct: res.headersDistinct?.['x-repeat'],
          location: res.headers.location,
          complete: res.complete,
          body: Buffer.concat(chunks).toString('utf8').slice(0, 400),
          length: Buffer.concat(chunks).length,
          events,
        });
      });
    });
    req.on('error', (e) => resolve({ error: e.code ?? e.name, name: e.name, events }));
    req.on('socket', () => events.push('socket'));
    req.on('finish', () => events.push('finish'));
    if (after) after(req);
    else if (body === undefined) req.end();
    else req.end(body);
  });
}

export async function run() {
  const out = {};
  out.constants = { methods: METHODS.length, teapot: STATUS_CODES[418], hasPost: METHODS.includes('POST') };
  out.json = await call('/json');
  out.head = await call('/json', { method: 'HEAD' });
  out.status = await call('/status/418');
  out.redirect = await call('/redirect');
  out.wholeBody = await call('/echo', { method: 'POST', headers: { 'content-type': 'text/plain', 'x-custom': 'yes' } }, 'héllo');
  out.binary = await call('/echo', { method: 'PUT' }, Buffer.from([0xff, 0, 0x80, 0xc3, 0x28]));
  out.streamed = await call('/echo', { method: 'POST' }, undefined, (req) => {
    req.write('part one, ');
    req.write(Buffer.from('part two, '));
    setTimeout(() => req.end('the end'), 20);
  });
  out.withLength = await call('/echo', { method: 'POST', headers: { 'Content-Length': 6 } }, undefined, (req) => {
    req.write('abc');
    req.end('def');
  });
  out.auth = await call('/echo', { auth: 'user:pa ss' });
  out.urlAuth = await new Promise((resolve) => {
    const u = new URL(base + '/echo');
    u.username = 'me';
    u.password = 'secret';
    get(u, (res) => { let t = ''; res.on('data', (c) => (t += c)); res.on('end', () => resolve(JSON.parse(t).authorization)); });
  });
  out.big = await call('/big');
  out.ignored = await new Promise((resolve) => {
    // No 'response' listener: Node discards the body, and the request still closes.
    const req = request(base + '/big');
    req.on('close', () => resolve('closed'));
    req.end();
  });
  out.timeout = await new Promise((resolve) => {
    const req = get(base + '/slow');
    req.setTimeout(150, () => { req.destroy(); resolve('timed out'); });
    req.on('error', () => {});
    req.on('response', () => resolve('answered'));
  });
  out.aborted = await new Promise((resolve) => {
    const controller = new AbortController();
    const req = get(base + '/slow', { signal: controller.signal });
    req.on('error', (e) => resolve([e.name, e.code]));
    setTimeout(() => controller.abort(), 50);
  });
  out.hangUp = await new Promise((resolve) => {
    const req = get(base + '/slow');
    req.on('error', (e) => resolve([e.code, e.message]));
    setTimeout(() => req.destroy(), 50);
  });
  out.refused = await new Promise((resolve) => {
    const req = get('http://127.0.0.1:9/');
    req.on('error', (e) => resolve(e.code));
  });
  out.wrongProtocol = (() => { try { http.request('https://example.com/'); return 'sent'; } catch (e) { return e.code; } })();
  out.badHeader = (() => { try { validateHeaderName('bad header'); return 'ok'; } catch (e) { return e.code; } })();
  out.badValue = (() => {
    const req = request(base + '/json');
    try { req.setHeader('x-a', 'line\nbreak'); return 'set'; } catch (e) { return e.code; } finally { req.destroy(); req.on('error', () => {}); }
  })();
  out.headerApi = (() => {
    const req = request(base + '/json');
    req.on('error', () => {});
    req.setHeader('X-One', '1');
    req.setHeader('x-many', ['a', 'b']);
    const result = { one: req.getHeader('x-one'), names: req.getHeaderNames().sort(), has: req.hasHeader('X-MANY'), sent: req.headersSent };
    req.removeHeader('x-one');
    result.after = req.getHeaderNames().sort();
    req.destroy();
    return result;
  })();
  out.agents = { http: http.globalAgent.defaultPort, https: https.globalAgent.defaultPort, protocol: new https.Agent().protocol };
  return out;
}
