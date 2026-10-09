// A stand-in for Node's `http` (D-109): the client, on a host request whose body streams, through the same egress gate
// as fetch (D-108). No sockets: the host connects to the address the gate checked, and `req.socket` is a stand-in
// with the methods libraries call. node:https is this module with TLS (js/node/https.js).
import { Buffer } from 'node:buffer';
import { EventEmitter } from 'node:events';
import { Readable, Writable } from 'node:stream';
import { nextTick } from 'node:process';
import { checkServerIdentity as defaultIdentityCheck, peerCertificate } from 'node:tls';
import { Socket as NetSocket, connect as netConnect, isIP } from 'node:net';
import { connect as tlsConnect } from 'node:tls';

const internals = globalThis[Symbol.for('automately.http')];
const reportUncaught = globalThis[Symbol.for('automately.uncaught')];
/** Runs a host callback's work; a throw from a listener it calls ends the job, as in Node, instead of vanishing. */
const surfaced = (fn) => (...args) => { try { return fn(...args); } catch (e) { reportUncaught(e, 'an http event listener'); return undefined; } };

export const METHODS = Object.freeze(['ACL', 'BIND', 'CHECKOUT', 'CONNECT', 'COPY', 'DELETE', 'GET', 'HEAD', 'LINK', 'LOCK',
  'M-SEARCH', 'MERGE', 'MKACTIVITY', 'MKCALENDAR', 'MKCOL', 'MOVE', 'NOTIFY', 'OPTIONS', 'PATCH', 'POST', 'PROPFIND',
  'PROPPATCH', 'PURGE', 'PUT', 'QUERY', 'REBIND', 'REPORT', 'SEARCH', 'SOURCE', 'SUBSCRIBE', 'TRACE', 'UNBIND', 'UNLINK',
  'UNLOCK', 'UNSUBSCRIBE']);

export const STATUS_CODES = Object.freeze({
  100: 'Continue', 101: 'Switching Protocols', 102: 'Processing', 103: 'Early Hints', 200: 'OK', 201: 'Created',
  202: 'Accepted', 203: 'Non-Authoritative Information', 204: 'No Content', 205: 'Reset Content', 206: 'Partial Content',
  207: 'Multi-Status', 208: 'Already Reported', 226: 'IM Used', 300: 'Multiple Choices', 301: 'Moved Permanently',
  302: 'Found', 303: 'See Other', 304: 'Not Modified', 305: 'Use Proxy', 307: 'Temporary Redirect',
  308: 'Permanent Redirect', 400: 'Bad Request', 401: 'Unauthorized', 402: 'Payment Required', 403: 'Forbidden',
  404: 'Not Found', 405: 'Method Not Allowed', 406: 'Not Acceptable', 407: 'Proxy Authentication Required',
  408: 'Request Timeout', 409: 'Conflict', 410: 'Gone', 411: 'Length Required', 412: 'Precondition Failed',
  413: 'Payload Too Large', 414: 'URI Too Long', 415: 'Unsupported Media Type', 416: 'Range Not Satisfiable',
  417: 'Expectation Failed', 418: "I'm a Teapot", 421: 'Misdirected Request', 422: 'Unprocessable Entity', 423: 'Locked',
  424: 'Failed Dependency', 425: 'Too Early', 426: 'Upgrade Required', 428: 'Precondition Required',
  429: 'Too Many Requests', 431: 'Request Header Fields Too Large', 451: 'Unavailable For Legal Reasons',
  500: 'Internal Server Error', 501: 'Not Implemented', 502: 'Bad Gateway', 503: 'Service Unavailable',
  504: 'Gateway Timeout', 505: 'HTTP Version Not Supported', 506: 'Variant Also Negotiates', 507: 'Insufficient Storage',
  508: 'Loop Detected', 509: 'Bandwidth Limit Exceeded', 510: 'Not Extended', 511: 'Network Authentication Required',
});

/** The most header bytes a response may have; the host's limit (Node's default is 16 KiB). */
export const maxHeaderSize = 65536;

const TOKEN = /^[\^_`a-zA-Z\-0-9!#$%&'*+.|~]+$/;
const INVALID_VALUE = /[^\t\x20-\x7e\x80-\xff]/;

function nodeError(Type, code, message) {
  const err = new Type(message);
  err.code = code;
  return err;
}

export function validateHeaderName(name, label = 'Header name') {
  if (typeof name !== 'string' || !name || !TOKEN.test(name)) {
    throw nodeError(TypeError, 'ERR_INVALID_HTTP_TOKEN', `${label} must be a valid HTTP token ["${name}"]`);
  }
}

export function validateHeaderValue(name, value) {
  if (value === undefined) throw nodeError(TypeError, 'ERR_HTTP_INVALID_HEADER_VALUE', `Invalid value "${value}" for header "${name}"`);
  if (INVALID_VALUE.test(String(value))) throw nodeError(TypeError, 'ERR_INVALID_CHAR', `Invalid character in header content ["${name}"]`);
}

// --- agents: accepted, with their TLS options; pooling is the host's ---

export class Agent extends EventEmitter {
  constructor(options = {}) {
    super();
    this.options = { __proto__: null, ...options };
    this.defaultPort = 80;
    this.protocol = 'http:';
    this.keepAlive = Boolean(options.keepAlive);
    this.keepAliveMsecs = options.keepAliveMsecs ?? 1000;
    this.maxSockets = options.maxSockets ?? Infinity;
    this.maxFreeSockets = options.maxFreeSockets ?? 256;
    this.maxTotalSockets = options.maxTotalSockets ?? Infinity;
    this.scheduling = options.scheduling ?? 'lifo';
    this.requests = { __proto__: null };
    this.sockets = { __proto__: null };
    this.freeSockets = { __proto__: null };
  }
  static get defaultMaxSockets() { return Infinity; }
  getName(options = {}) { return `${options.host ?? 'localhost'}:${options.port ?? ''}:${options.localAddress ?? ''}`; }
  destroy() {}
}
export const globalAgent = new Agent({ keepAlive: true, scheduling: 'lifo', timeout: 5000 });

// --- the socket stand-in: what libraries call on req.socket and res.socket ---

class SocketStandIn extends EventEmitter {
  constructor(request, encrypted) {
    super();
    this._request = request;
    this.encrypted = encrypted;
    // Until the host has the connection, as a Node socket that's still connecting (D-114).
    this.connecting = true;
    this.pending = true;
    this.authorized = false;
    this.authorizationError = undefined;
    this.alpnProtocol = false;
    this.servername = encrypted && !/^[\d.]+$|:/.test(request.host) ? request.host : undefined;
    this.readable = true;
    this.writable = true;
    this.destroyed = false;
    this.remoteAddress = undefined;
    this.remotePort = undefined;
    this.remoteFamily = undefined;
    this.localAddress = undefined;
    this.localPort = undefined;
    this.bytesRead = 0;
    this.bytesWritten = 0;
    this._tls = null;
  }
  get readyState() { return this.connecting ? 'opening' : this.destroyed ? 'closed' : 'open'; }

  /** The host connected: take on its addresses and TLS details, then announce it in Node's order. */
  _connected(info, host) {
    if (!info || !this.connecting) return;
    this.connecting = false;
    this.pending = false;
    this.remoteAddress = info.remoteAddress;
    this.remotePort = info.remotePort;
    this.remoteFamily = info.remoteFamily;
    this.localAddress = info.localAddress;
    this.localPort = info.localPort;
    this._request.reusedSocket = Boolean(info.reused);
    if (this.encrypted && info.tls) {
      this._tls = info.tls;
      this.authorized = Boolean(info.tls.authorized);
      this.authorizationError = info.tls.authorizationError;
      this.alpnProtocol = info.tls.alpnProtocol ?? false;
    }
    // Node looks up names only, never an address; and a socket from the pool was looked up for an earlier request.
    if (!info.reused && this.remoteAddress && !/^[\d.]+$|:/.test(host)) {
      this.emit('lookup', null, this.remoteAddress, this.remoteFamily === 'IPv6' ? 6 : 4, host);
    }
    this.emit('connect');
    if (this.encrypted) this.emit('secureConnect');
    this.emit('ready');
  }

  setTimeout(ms, callback) { this._request.setTimeout(ms, callback); return this; }
  setNoDelay() { return this; }
  setKeepAlive() { return this; }
  ref() { return this; }
  unref() { return this; }
  address() {
    return this.localAddress === undefined ? {} : { address: this.localAddress, family: this.remoteFamily, port: this.localPort };
  }
  getPeerCertificate(detailed) { return this._tls ? peerCertificate(this._tls, detailed) : this.encrypted ? {} : null; }
  getProtocol() { return this._tls?.protocol ?? null; }
  getCipher() {
    if (!this._tls?.cipher) return undefined;
    return { name: this._tls.cipher, standardName: this._tls.cipher, version: this._tls.protocol };
  }
  getSession() { return undefined; }
  isSessionReused() { return false; }
  getEphemeralKeyInfo() { return {}; }
  getFinished() { return undefined; }
  getPeerFinished() { return undefined; }
  destroy(err) { this._request.destroy(err); return this; }
  end() { return this; }
}

// --- the response ---

// Node keeps the first of these when a response repeats them, rather than joining (lib/_http_incoming.js).
const FIRST_WINS = new Set(['content-type', 'content-length', 'user-agent', 'referer', 'host', 'authorization',
  'proxy-authorization', 'if-modified-since', 'if-unmodified-since', 'from', 'location', 'max-forwards', 'retry-after',
  'etag', 'last-modified', 'server', 'age', 'expires']);

function headersOf(raw) {
  const headers = { __proto__: null };
  const distinct = { __proto__: null };
  for (let i = 0; i + 1 < raw.length; i += 2) {
    const name = String(raw[i]).toLowerCase();
    const value = String(raw[i + 1]);
    (distinct[name] ??= []).push(value);
    if (!(name in headers)) headers[name] = name === 'set-cookie' ? [value] : value;
    else if (name === 'set-cookie') headers[name].push(value);
    else if (FIRST_WINS.has(name)) continue;
    else headers[name] += (name === 'cookie' ? '; ' : ', ') + value;
  }
  return { headers: { ...headers }, distinct: { ...distinct } };
}

export class IncomingMessage extends Readable {
  constructor(socket) {
    super();
    this.socket = socket;
    this.httpVersionMajor = 1;
    this.httpVersionMinor = 1;
    this.httpVersion = '1.1';
    this.complete = false;
    this.aborted = false;
    this.rawHeaders = [];
    this.headers = {};
    this.headersDistinct = {};
    this.rawTrailers = [];
    this.trailers = {};
    this.trailersDistinct = {};
    this.url = '';
    this.method = null;
    this.statusCode = null;
    this.statusMessage = null;
    this._body = null;
    this._pulling = false;
  }
  get connection() { return this.socket; }
  setTimeout(ms, callback) { this.socket?.setTimeout(ms, callback); return this; }

  _read() {
    if (this._pulling || !this._body) return;
    this._pulling = true;
    this._body.next().then(surfaced(({ value, done }) => {
      this._pulling = false;
      if (done) {
        this.complete = true;
        this.push(null);
        return;
      }
      if (this.socket) this.socket.bytesRead += value.byteLength;
      if (this.push(Buffer.from(value.buffer, value.byteOffset, value.byteLength))) this._read();
    }), surfaced((err) => {
      this._pulling = false;
      this.aborted = true;
      this.destroy(networkError(err));
    }));
  }

  _destroy(err, callback) {
    if (!this.readableEnded || !this.complete) {
      this.aborted = true;
      this.emit('aborted');
    }
    try { this._body?.return(); } catch { /* already finished */ }
    // As Node's IncomingMessage: an error is reported only to someone listening for one (lib/_http_incoming.js).
    callback(this.listenerCount('error') > 0 ? err : undefined);
  }
}

// --- the request ---

/** A host failure in Node's terms, so libraries that look at err.code (retries, axios, got) see the codes they expect. */
/**
 * A host failure as Node's error: its code, and for a refused connection or an unknown name Node's message and
 * properties (`connect ECONNREFUSED 10.0.0.1:5432`, `getaddrinfo ENOTFOUND db.example`). {@code req} gives the target.
 */
function networkError(err, req) {
  if (!err || typeof err !== 'object') return nodeError(Error, 'ECONNRESET', String(err));
  const message = String(err.message ?? err);
  let code = err.code;
  if (code === 'dns_failed') code = 'ENOTFOUND';
  else if (code === 'timeout') code = 'ETIMEDOUT';
  else if (code === 'aborted') code = 'ECONNRESET';
  else if (code === 'network_error' || !code) code = /refused/i.test(message) ? 'ECONNREFUSED' : 'ECONNRESET';
  let out = err instanceof Error ? err : new Error(message);
  if (req && code === 'ECONNREFUSED') {
    // The host's message names the address it tried: the one the egress gate checked.
    const tried = /\/([0-9a-fA-F.:]+):(\d+)\s*$/.exec(message);
    const address = tried ? tried[1] : req.host;
    const port = tried ? Number(tried[2]) : req._port;
    out = Object.assign(new Error(`connect ECONNREFUSED ${address.includes(':') ? `[${address}]` : address}:${port}`),
      { errno: -111, syscall: 'connect', address, port });
  } else if (req && code === 'ENOTFOUND') {
    out = Object.assign(new Error(`getaddrinfo ENOTFOUND ${req.host}`), { errno: -3008, syscall: 'getaddrinfo', hostname: req.host });
  }
  try { out.code = code; } catch { /* frozen */ }
  return out;
}

const REFUSED_OPTIONS = {
  socketPath: 'Unix sockets are not available in the isolate',
  checkServerIdentity: "a custom certificate check isn't supported for http requests yet; use the ca option (tls.connect takes one)",
  pfx: 'PFX bundles are not supported; pass PEM cert and key',
  passphrase: 'encrypted private keys are not supported; pass an unencrypted PEM key',
};

let warnedInsecure = false;

export class ClientRequest extends Writable {
  /** {@code defaults}: http's, or https's (protocol, port, agent, tls); not part of Node's signature. */
  constructor(input, options, callback, defaults = HTTP) {
    // Not destroyed on 'finish' as a plain Writable is: the response comes after the body is sent.
    super({ decodeStrings: true, autoDestroy: false });
    if (typeof options === 'function') { callback = options; options = undefined; }
    defaults = agentDefaults(input, options, defaults);
    const target = mergeTarget(input, options, defaults);
    for (const [name, why] of Object.entries(REFUSED_OPTIONS)) {
      if (name === 'checkServerIdentity' && target[name] === defaultIdentityCheck) continue;
      if (target[name] !== undefined && target[name] !== null) {
        throw nodeError(Error, 'ERR_NOT_SUPPORTED', `The "${name}" option isn't supported in the isolate: ${why}`);
      }
    }
    const protocol = target.protocol ?? defaults.protocol;
    if (protocol !== defaults.protocol) {
      throw nodeError(TypeError, 'ERR_INVALID_PROTOCOL', `Protocol "${protocol}" not supported. Expected "${defaults.protocol}"`);
    }
    const method = String(target.method ?? 'GET').toUpperCase();
    validateHeaderName(method, 'Method');
    if (method === 'CONNECT') throw nodeError(Error, 'ERR_NOT_SUPPORTED', 'CONNECT tunnels are not available in the isolate');
    const host = target.hostname ?? (typeof target.host === 'string' ? target.host.replace(/:\d+$/, '') : 'localhost');
    const port = Number(target.port ?? target.defaultPort ?? target.agent?.defaultPort ?? defaults.defaultPort);
    const path = String(target.path ?? '/');
    if (/[\u0000- ]/.test(path)) throw nodeError(TypeError, 'ERR_UNESCAPED_CHARACTERS', 'Request path contains unescaped characters');

    this.method = method;
    this.path = path;
    this.host = host;
    this.protocol = protocol;
    this.agent = target.agent === false ? undefined : (target.agent ?? defaults.agent);
    this.reusedSocket = false;
    this.maxHeadersCount = null;
    this.aborted = false;
    this.timeoutCb = null;
    this.res = null;
    this._port = port;
    this._encrypted = Boolean(defaults.tls);
    this._done = false;
    this._headers = new Map();
    this._sentHeaders = false;
    this._id = null;
    this._opening = null;
    this._wroteBody = false;
    this._timer = null;
    this._timeoutMs = 0;
    this._tls = this._encrypted ? tlsOptions({ ...(this.agent?.options ?? {}), ...target }) : null;
    this.socket = new SocketStandIn(this, this._encrypted);

    const headers = target.headers;
    if (Array.isArray(headers)) {
      for (let i = 0; i + 1 < headers.length; i += 2) this.appendHeader(headers[i], headers[i + 1]);
    } else if (headers) {
      for (const [k, v] of Object.entries(headers)) if (v !== undefined) this.setHeader(k, v);
    }
    if (target.setHost !== false && !this.hasHeader('host')) {
      const bracketed = host.includes(':') ? `[${host}]` : host;
      this.setHeader('Host', port === defaults.defaultPort ? bracketed : `${bracketed}:${port}`);
    }
    if (target.auth && !this.hasHeader('authorization')) {
      this.setHeader('Authorization', 'Basic ' + Buffer.from(String(target.auth)).toString('base64'));
    }
    if (callback) this.once('response', callback);
    // A request that asks to upgrade (a WebSocket), or brings its own connection, is written on a socket here (D-125).
    this._viaSocket = typeof target.createConnection === 'function' || asksToUpgrade(this);
    if (this._viaSocket) this._connectSocket(target);
    if (target.timeout !== undefined) this.setTimeout(target.timeout);
    if (target.signal) {
      const signal = target.signal;
      const onAbort = () => this.destroy(abortError(signal.reason));
      if (signal.aborted) nextTick(onAbort);
      else {
        signal.addEventListener('abort', onAbort, { once: true });
        this.once('close', () => signal.removeEventListener('abort', onAbort));
      }
    }
    nextTick(() => this.emit('socket', this.socket));
  }

  get connection() { return this.socket; }
  get headersSent() { return this._sentHeaders; }
  get finished() { return this.writableEnded; }

  setHeader(name, value) {
    if (this._sentHeaders) throw nodeError(Error, 'ERR_HTTP_HEADERS_SENT', 'Cannot set headers after they are sent to the client');
    validateHeaderName(name);
    validateHeaderValue(name, value);
    this._headers.set(String(name).toLowerCase(), { name: String(name), value });
    return this;
  }
  appendHeader(name, value) {
    const existing = this.getHeader(name);
    if (existing === undefined) return this.setHeader(name, value);
    const all = [].concat(existing, value);
    return this.setHeader(name, all);
  }
  getHeader(name) { return this._headers.get(String(name).toLowerCase())?.value; }
  hasHeader(name) { return this._headers.has(String(name).toLowerCase()); }
  removeHeader(name) {
    if (this._sentHeaders) throw nodeError(Error, 'ERR_HTTP_HEADERS_SENT', 'Cannot remove headers after they are sent to the client');
    this._headers.delete(String(name).toLowerCase());
  }
  getHeaders() {
    const out = { __proto__: null };
    for (const [key, { value }] of this._headers) out[key] = value;
    return out;
  }
  getHeaderNames() { return [...this._headers.keys()]; }
  getRawHeaderNames() { return [...this._headers.values()].map((h) => h.name); }
  flushHeaders() { this._open(true).catch(() => {}); }
  setNoDelay() {}
  setSocketKeepAlive() {}

  setTimeout(ms, callback) {
    if (callback) this.once('timeout', callback);
    this._timeoutMs = Number(ms) || 0;
    this._touch();
    return this;
  }
  _touch() {
    if (this._timer) clearTimeout(this._timer);
    this._timer = null;
    if (this._timeoutMs > 0 && !this.destroyed) {
      this._timer = setTimeout(() => { this._timer = null; this.emit('timeout'); this.socket.emit('timeout'); }, this._timeoutMs);
    }
  }

  abort() {
    if (this.aborted) return;
    this.aborted = true;
    nextTick(() => this.emit('abort'));
    this.destroy();
  }

  /** Connects (once): the headers go to the host, which sends them with the first chunk or the end. */
  _open(chunked) {
    if (this._opening) return this._opening;
    if (this._viaSocket) return (this._opening = Promise.resolve(this._writeHead(chunked)));
    this._sentHeaders = true;
    const headers = {};
    for (const { name, value } of this._headers.values()) headers[name] = Array.isArray(value) ? value.map(String) : String(value);
    const authority = this.host.includes(':') ? `[${this.host}]` : this.host;
    const url = `${this.protocol}//${authority}:${this._port}${this.path.startsWith('/') ? '' : '/'}${this.path}`;
    this._opening = internals.open({ url, method: this.method, headers, chunked, tls: this._tls }).then(({ id, connection }) => {
      this._id = id;
      if (this.destroyed) { internals.abort(id); throw this._closedError(); }
      surfaced(() => this.socket._connected(connection, this.host))();
      internals.response(id).then(surfaced((r) => this._respond(r)), surfaced((err) => this.destroy(networkError(err, this))));
      return id;
    });
    this._opening.catch(surfaced((err) => this.destroy(networkError(err, this))));
    return this._opening;
  }
  _closedError() { return nodeError(Error, 'ECONNRESET', 'socket hang up'); }

  _respond({ meta, body }) {
    if (this.destroyed) { body.cancel(); return; }
    const res = new IncomingMessage(this.socket);
    const { headers, distinct } = headersOf(meta.rawHeaders ?? []);
    res.statusCode = meta.status;
    res.statusMessage = meta.statusText || STATUS_CODES[meta.status] || '';
    res.rawHeaders = (meta.rawHeaders ?? []).map(String);
    res.headers = headers;
    res.headersDistinct = distinct;
    res.httpVersion = meta.httpVersion ?? '1.1';
    res.httpVersionMinor = res.httpVersion === '1.0' ? 0 : 1;
    res.req = this;
    res._body = body[Symbol.asyncIterator]();
    this._respondWith(res);
  }

  /** Announces a response: 'response' once the body is sent (Node's order), and the request is done when it ends. */
  _respondWith(res) {
    this.res = res;
    res.on('data', () => this._touch());
    // Once the response is complete the request is done: it closes without aborting, so the host can reuse the connection.
    res.on('end', () => { this._done = true; this._clearTimer(); nextTick(() => this.destroy()); });
    this._touch();
    const emit = () => { if (!this.emit('response', res)) res.resume(); };
    // Once the body is ending, 'finish' comes first, as in Node; a server answering mid-upload is answered at once.
    if (this._ending && !this.writableFinished) this.once('finish', emit);
    else emit();
  }

  _clearTimer() { if (this._timer) { clearTimeout(this._timer); this._timer = null; } }

  _write(chunk, encoding, callback) {
    if (this._viaSocket) { this._socketWrite(chunk, callback); return; }
    this._wroteBody = true;
    const bytes = chunk.length;
    this._open(!this.hasHeader('content-length')).then((id) => internals.write(id, chunk)).then(surfaced(() => {
      this.socket.bytesWritten += bytes;
      this._touch();
      callback();
    }), surfaced((err) => callback(networkError(err, this))));
  }

  _final(callback) {
    if (this._viaSocket) { this._socketFinal(callback); return; }
    this._ending = true;
    this._open(this._wroteBody && !this.hasHeader('content-length')).then((id) => internals.end(id))
      .then(surfaced(() => callback()), surfaced((err) => callback(networkError(err, this))));
  }

  end(chunk, encoding, callback) {
    if (typeof chunk === 'function') { callback = chunk; chunk = undefined; }
    else if (typeof encoding === 'function') { callback = encoding; encoding = undefined; }
    // One body given whole, before anything was sent: Node sends its length rather than chunks (S3 and others need it).
    if (!this._sentHeaders && !this._wroteBody && !this.hasHeader('content-length') && !this.hasHeader('transfer-encoding')) {
      const length = chunk === undefined || chunk === null ? 0 : typeof chunk === 'string' ? Buffer.byteLength(chunk, encoding) : chunk.byteLength;
      if (length > 0 || !['GET', 'HEAD', 'DELETE', 'OPTIONS', 'TRACE'].includes(this.method)) this.setHeader('Content-Length', length);
    }
    return super.end(chunk, encoding, callback);
  }

  _destroy(err, callback) {
    this._clearTimer();
    if (this._viaSocket) { this._socketDestroy(err, callback); return; }
    if (this._done) { callback(err); return; }
    if (this._id !== null) internals.abort(this._id);
    if (this.res && !this.res.readableEnded) this.res.destroy(err ?? this._closedError());
    // Destroyed before its response, a request fails with "socket hang up", as Node's does; abort() doesn't fail it.
    callback(err ?? (this.res || this.aborted ? null : this._closedError()));
  }
}

// --- requests over a socket the script drives (D-125) ---
// As Node's client: HTTP/1.1 written on a net or tls socket (or the one createConnection returns), and the response
// parsed here. A 101 hands the socket to 'upgrade'. These sockets go through the egress gate as tcp connections, since
// the script writes their bytes. The connection isn't kept for another request.

const ClientRequestSocket = {
  _connectSocket(target) {
    const options = { ...target, host: this.host, port: this._port };
    let socket;
    if (typeof target.createConnection === 'function') {
      let settled = false;
      const created = target.createConnection(options, (err, made) => {
        if (settled) return;
        settled = true;
        if (err) nextTick(() => this.destroy(err));
        else if (made) this._attachSocket(made);
      });
      if (created && !settled) { settled = true; socket = created; }
    } else if (this._encrypted) {
      socket = tlsConnect({ ...this._tls, host: this.host, port: this._port, servername: isIP(this.host) ? undefined : this.host });
    } else {
      socket = netConnect({ host: this.host, port: this._port });
    }
    if (socket) this._attachSocket(socket);
    else this._pendingWrites = [];
  },

  _attachSocket(socket) {
    this.socket = socket;
    this._parser = { state: 'head', buffer: Buffer.alloc(0), res: null, remaining: 0, chunk: 'size', queue: null };
    this._onSocketData = (data) => this._socketData(data);
    this._onSocketEnd = () => this._socketEnded();
    this._onSocketError = (err) => { if (!this._handedOver) this.destroy(err); };
    this._onSocketClose = () => { if (!this._handedOver) this._socketEnded(); };
    socket.on('data', this._onSocketData);
    socket.on('end', this._onSocketEnd);
    socket.on('error', this._onSocketError);
    socket.on('close', this._onSocketClose);
    for (const go of this._pendingWrites?.splice(0) ?? []) go();
  },

  /** Runs once the socket is there: createConnection may hand it over later. */
  _withSocket(go) {
    if (this.socket instanceof SocketStandIn) (this._pendingWrites ??= []).push(go);
    else go();
  },

  _writeHead(chunked) {
    this._sentHeaders = true;
    let head = `${this.method} ${this.path} HTTP/1.1\r\n`;
    for (const { name, value } of this._headers.values()) {
      for (const one of [].concat(value)) head += `${name}: ${one}\r\n`;
    }
    this._chunked = chunked && !this.hasHeader('content-length') && !this.hasHeader('transfer-encoding');
    if (this._chunked) head += 'Transfer-Encoding: chunked\r\n';
    // As Node's request with no agent: the connection isn't kept, unless the request says otherwise.
    if (!this.hasHeader('connection')) head += 'Connection: close\r\n';
    this._withSocket(() => this.socket.write(`${head}\r\n`, 'latin1'));
  },

  _socketWrite(chunk, callback) {
    this._wroteBody = true;
    this._open(!this.hasHeader('content-length'));
    this._withSocket(() => {
      if (this._chunked) this.socket.write(`${chunk.length.toString(16)}\r\n`, 'latin1');
      this.socket.write(chunk);
      if (this._chunked) this.socket.write('\r\n', 'latin1', () => callback());
      else this.socket.write(Buffer.alloc(0), () => callback());
    });
  },

  _socketFinal(callback) {
    this._ending = true;
    this._open(this._wroteBody && !this.hasHeader('content-length'));
    this._withSocket(() => {
      if (this._chunked) this.socket.write('0\r\n\r\n', 'latin1', () => callback());
      else callback();
    });
  },

  _socketDestroy(err, callback) {
    const socket = this.socket;
    if (!this._handedOver && socket && !(socket instanceof SocketStandIn)) {
      socket.removeListener('data', this._onSocketData);
      socket.destroy();
    }
    const queue = this._parser?.queue;
    if (queue && !queue.done) queue.fail(err ?? this._closedError());
    if (this._done || this._handedOver) { callback(err); return; }
    callback(err ?? (this.res || this.aborted ? null : this._closedError()));
  },

  _socketData(data) {
    const p = this._parser;
    this._touch();
    if (p.state === 'head') {
      p.buffer = p.buffer.length ? Buffer.concat([p.buffer, data]) : data;
      for (;;) {
        const end = p.buffer.indexOf('\r\n\r\n');
        if (end < 0) {
          if (p.buffer.length > maxHeaderSize) this.destroy(nodeError(Error, 'HPE_HEADER_OVERFLOW', 'Header overflow'));
          return;
        }
        const head = p.buffer.subarray(0, end).toString('latin1');
        const rest = p.buffer.subarray(end + 4);
        p.buffer = Buffer.alloc(0);
        const res = this._parseHead(head);
        if (!res) return;
        if (res.statusCode === 101) { this._upgraded(res, rest); return; }
        if (res.statusCode >= 100 && res.statusCode < 200) {
          // Informational: 'information' (and 'continue' for a 100), then the real head follows.
          this.emit('information', { statusCode: res.statusCode, statusMessage: res.statusMessage, httpVersion: res.httpVersion,
            httpVersionMajor: 1, httpVersionMinor: res.httpVersionMinor, headers: res.headers, rawHeaders: res.rawHeaders });
          if (res.statusCode === 100) this.emit('continue');
          p.buffer = rest;
          if (!rest.length) return;
          continue;
        }
        this._socketResponse(res);
        if (rest.length) this._socketBody(rest);
        return;
      }
    }
    if (p.state === 'body') this._socketBody(data);
  },

  _parseHead(head) {
    const lines = head.split('\r\n');
    const status = /^HTTP\/1\.([01]) (\d{3})(?: (.*))?$/.exec(lines[0]);
    if (!status) {
      this.destroy(nodeError(Error, 'HPE_INVALID_CONSTANT', 'Parse Error: Expected HTTP/'));
      return null;
    }
    const res = new IncomingMessage(this.socket);
    const raw = [];
    for (const line of lines.slice(1)) {
      const colon = line.indexOf(':');
      if (colon > 0) raw.push(line.slice(0, colon).trim(), line.slice(colon + 1).trim());
    }
    const { headers, distinct } = headersOf(raw);
    res.statusCode = Number(status[2]);
    res.statusMessage = status[3] ?? '';
    res.httpVersion = `1.${status[1]}`;
    res.httpVersionMinor = Number(status[1]);
    res.rawHeaders = raw;
    res.headers = headers;
    res.headersDistinct = distinct;
    res.req = this;
    return res;
  },

  /** A 101: the socket is the 'upgrade' listener's, with whatever came after the head; or closed if no one listens. */
  _upgraded(res, rest) {
    const socket = this.socket;
    this._handedOver = true;
    this._done = true;
    this._clearTimer();
    socket.removeListener('data', this._onSocketData);
    socket.removeListener('end', this._onSocketEnd);
    socket.removeListener('error', this._onSocketError);
    socket.removeListener('close', this._onSocketClose);
    // As Node's: no longer flowing, so the new owner's 'data' listener starts it.
    socket.pause();
    if (socket._readableState) socket._readableState.flowing = null;
    res.upgrade = true;
    res.complete = true;
    this.res = res;
    if (this.listenerCount('upgrade') > 0) {
      this.upgradeOrConnect = true;
      this.emit('upgrade', res, socket, rest);
      this.destroyed = true;
      nextTick(() => this.emit('close'));
    } else {
      socket.destroy();
      this.destroy();
    }
  },

  _socketResponse(res) {
    const p = this._parser;
    const queue = new BodyQueue(this.socket);
    p.queue = queue;
    p.res = res;
    res._body = queue;
    const length = res.headers['content-length'];
    const chunked = /\bchunked\b/i.test(String(res.headers['transfer-encoding'] ?? ''));
    if (this.method === 'HEAD' || res.statusCode === 204 || res.statusCode === 304) p.framing = 'none';
    else if (chunked) p.framing = 'chunked';
    else if (length !== undefined) { p.framing = 'length'; p.remaining = Number(length); }
    else p.framing = 'close';
    p.state = 'body';
    this._respondWith(res);
    if (p.framing === 'none' || (p.framing === 'length' && p.remaining === 0)) this._bodyDone();
  },

  _socketBody(data) {
    const p = this._parser;
    if (p.state !== 'body') return;
    if (p.framing === 'close') { p.queue.push(data); return; }
    if (p.framing === 'length') {
      const take = data.subarray(0, p.remaining);
      p.remaining -= take.length;
      if (take.length) p.queue.push(take);
      if (p.remaining === 0) this._bodyDone();
      return;
    }
    // chunked
    p.buffer = p.buffer.length ? Buffer.concat([p.buffer, data]) : data;
    for (;;) {
      if (p.chunk === 'size') {
        const eol = p.buffer.indexOf('\r\n');
        if (eol < 0) return;
        const size = parseInt(p.buffer.subarray(0, eol).toString('latin1').split(';')[0].trim(), 16);
        p.buffer = p.buffer.subarray(eol + 2);
        if (Number.isNaN(size)) { this.destroy(nodeError(Error, 'HPE_INVALID_CHUNK_SIZE', 'Parse Error: Invalid character in chunk size')); return; }
        if (size === 0) { p.chunk = 'trailers'; continue; }
        p.remaining = size;
        p.chunk = 'data';
      } else if (p.chunk === 'data') {
        if (!p.buffer.length) return;
        const take = p.buffer.subarray(0, p.remaining);
        p.remaining -= take.length;
        p.buffer = p.buffer.subarray(take.length);
        p.queue.push(take);
        if (p.remaining === 0) p.chunk = 'crlf';
      } else if (p.chunk === 'crlf') {
        if (p.buffer.length < 2) return;
        p.buffer = p.buffer.subarray(2);
        p.chunk = 'size';
      } else {
        const end = p.buffer.indexOf('\r\n\r\n');
        const bare = p.buffer.indexOf('\r\n');
        if (bare === 0) { this._bodyDone(); return; }
        if (end < 0) return;
        const raw = [];
        for (const line of p.buffer.subarray(0, end).toString('latin1').split('\r\n')) {
          const colon = line.indexOf(':');
          if (colon > 0) raw.push(line.slice(0, colon).trim(), line.slice(colon + 1).trim());
        }
        p.res.rawTrailers = raw;
        p.res.trailers = headersOf(raw).headers;
        this._bodyDone();
        return;
      }
    }
  },

  _bodyDone() {
    const p = this._parser;
    if (p.state === 'done') return;
    p.state = 'done';
    p.queue.end();
    // The connection isn't kept for another request.
    p.queue.onDrained = () => this.socket.destroy();
  },

  _socketEnded() {
    const p = this._parser;
    if (!p || p.state === 'done') return;
    if (p.state === 'body' && p.framing === 'close') { this._bodyDone(); return; }
    if (p.queue) p.queue.fail(this._closedError());
    else this.destroy(this._closedError());
  },
};

/** A response body as the IncomingMessage reads it: chunks from the socket, which pauses while too many wait. */
class BodyQueue {
  constructor(socket) {
    this.socket = socket;
    this.items = [];
    this.waiting = null;
    this.done = false;
    this.error = null;
    this.onDrained = null;
  }
  push(chunk) {
    if (this.waiting) { const w = this.waiting; this.waiting = null; w.resolve({ value: chunk, done: false }); return; }
    this.items.push(chunk);
    if (this.items.length > 16) this.socket.pause();
  }
  end() { this.done = true; this._settle(); }
  fail(err) { this.done = true; this.error = err; this._settle(); }
  _settle() {
    if (!this.waiting || this.items.length) return;
    const w = this.waiting;
    this.waiting = null;
    if (this.error) w.reject(this.error); else { w.resolve({ value: undefined, done: true }); this.onDrained?.(); }
  }
  next() {
    if (this.items.length) {
      const value = this.items.shift();
      if (this.items.length < 4 && !this.done) this.socket.resume();
      return Promise.resolve({ value, done: false });
    }
    if (this.done) {
      if (this.error) return Promise.reject(this.error);
      this.onDrained?.();
      return Promise.resolve({ value: undefined, done: true });
    }
    return new Promise((resolve, reject) => { this.waiting = { resolve, reject }; });
  }
  return() { this.done = true; this.items = []; this.socket.destroy(); return Promise.resolve({ value: undefined, done: true }); }
  [Symbol.asyncIterator]() { return this; }
}

/** Whether a request's own headers ask to upgrade: an Upgrade, and the upgrade token in Connection. */
function asksToUpgrade(req) {
  const connection = req.getHeader('connection');
  return req.getHeader('upgrade') !== undefined && connection !== undefined
    && [].concat(connection).some((v) => String(v).split(',').some((t) => t.trim().toLowerCase() === 'upgrade'));
}

Object.assign(ClientRequest.prototype, ClientRequestSocket);

function abortError(reason) {
  const err = new Error('The operation was aborted', { cause: reason });
  err.name = 'AbortError';
  err.code = 'ABORT_ERR';
  return err;
}

/** The target from request(url, options) or request(options): a URL's parts, then the options over them. */
function mergeTarget(input, options, defaults) {
  let target = {};
  if (typeof input === 'string' || input instanceof URL) {
    const url = input instanceof URL ? input : new URL(input);
    target = {
      protocol: url.protocol,
      hostname: url.hostname.startsWith('[') ? url.hostname.slice(1, -1) : url.hostname,
      port: url.port === '' ? undefined : Number(url.port),
      path: `${url.pathname || '/'}${url.search}`,
      auth: url.username || url.password ? `${decodeURIComponent(url.username)}:${decodeURIComponent(url.password)}` : undefined,
    };
    if (options) target = { ...target, ...options };
  } else {
    target = { ...(input ?? {}), ...(options ?? {}) };
  }
  if (target.agent && target.agent.protocol && !target.protocol) target.protocol = target.agent.protocol;
  target.defaultPort ??= target.agent?.defaultPort ?? defaults.defaultPort;
  return target;
}

/** The TLS options the host honours: rejectUnauthorized, and PEM ca, cert and key. */
function tlsOptions(target) {
  const out = {};
  let reject = target.rejectUnauthorized;
  if (reject === undefined && globalThis.process?.env?.NODE_TLS_REJECT_UNAUTHORIZED === '0') reject = false;
  if (reject === false) {
    out.rejectUnauthorized = false;
    if (!warnedInsecure) {
      warnedInsecure = true;
      console.warn('https: certificate verification is off for a request (rejectUnauthorized: false); only the job itself is affected');
    }
  }
  const pem = (value) => (value === undefined || value === null ? undefined
    : Array.isArray(value) ? value.map(pem) : typeof value === 'string' ? value : Buffer.from(value).toString('utf8'));
  if (target.ca !== undefined) out.ca = pem(target.ca);
  if (target.cert !== undefined) out.cert = pem(Array.isArray(target.cert) ? target.cert[0] : target.cert);
  if (target.key !== undefined) out.key = pem(Array.isArray(target.key) ? target.key[0] : target.key);
  return out;
}

/**
 * As Node's ClientRequest: the protocol expected is the agent's, or else `_defaultAgent`'s (http2-wrapper, and so got,
 * sends https requests through http.request with https's agent as `_defaultAgent`).
 */
function agentDefaults(input, options, defaults) {
  const given = options && typeof options === 'object' ? options
    : input && typeof input === 'object' && !(input instanceof URL) ? input : null;
  const agent = given?.agent && typeof given.agent === 'object' && given.agent.protocol ? given.agent
    : given?._defaultAgent?.protocol ? given._defaultAgent : null;
  if (!agent || agent.protocol === defaults.protocol || (agent.protocol !== 'http:' && agent.protocol !== 'https:')) return defaults;
  return { protocol: agent.protocol, defaultPort: agent.defaultPort ?? (agent.protocol === 'https:' ? 443 : 80),
    tls: agent.protocol === 'https:', agent };
}

const HTTP = { protocol: 'http:', defaultPort: 80, tls: false, get agent() { return globalAgent; } };

export function request(input, options, callback) {
  return new ClientRequest(input, options, callback, HTTP);
}

export function get(input, options, callback) {
  const req = request(input, options, callback);
  req.end();
  return req;
}

// --- servers (D-112, D-123) ---
// Node's http.Server, as lib/_http_server.js and lib/_http_outgoing.js behave. Nothing listens on the node: listen(port)
// registers the port with the host, and the cluster's ingress routes the port's host to it (<service>--<tenant>--<port>,
// the first server also answering the service's own host). Requests arrive as the client sent them (its raw headers,
// address and HTTP version), and responses stream back with backpressure.

const server = globalThis[Symbol.for('automately.server')];
const kHeaders = Symbol('kOutHeaders');

function headersSentError(action) {
  return nodeError(Error, 'ERR_HTTP_HEADERS_SENT', `Cannot ${action} headers after they are sent to the client`);
}

/** As Node's OutgoingMessage: the header API both ServerResponse and (in Node) ClientRequest share. */
export class OutgoingMessage extends Writable {
  constructor(options) {
    super({ highWaterMark: options?.highWaterMark ?? 16 * 1024 });
    this[kHeaders] = null;
    this.sendDate = false;
    this.chunkedEncoding = false;
    this.useChunkedEncodingByDefault = true;
    this.shouldKeepAlive = true;
    this.strictContentLength = false;
    this._headerSent = false;
    this._trailers = null;
    this.socket = null;
  }
  get connection() { return this.socket; }
  get headersSent() { return this._headerSent; }
  get finished() { return this.writableEnded; }

  setHeader(name, value) {
    if (this._headerSent) throw headersSentError('set');
    validateHeaderName(name);
    validateHeaderValue(name, value);
    (this[kHeaders] ??= new Map()).set(name.toLowerCase(), [name, value]);
    return this;
  }

  appendHeader(name, value) {
    if (this._headerSent) throw headersSentError('append');
    validateHeaderName(name);
    validateHeaderValue(name, value);
    const key = name.toLowerCase();
    const existing = this[kHeaders]?.get(key);
    if (!existing) return this.setHeader(name, value);
    const before = Array.isArray(existing[1]) ? existing[1] : [existing[1]];
    existing[1] = before.concat(value);
    return this;
  }

  getHeader(name) {
    if (typeof name !== 'string') throw nodeError(TypeError, 'ERR_INVALID_ARG_TYPE', `The "name" argument must be of type string. Received ${typeof name}`);
    return this[kHeaders]?.get(name.toLowerCase())?.[1];
  }

  getHeaders() {
    const out = { __proto__: null };
    for (const [key, [, value]] of this[kHeaders] ?? []) out[key] = value;
    return out;
  }

  getHeaderNames() { return [...(this[kHeaders]?.keys() ?? [])]; }
  getRawHeaderNames() { return [...(this[kHeaders]?.values() ?? [])].map(([name]) => name); }

  hasHeader(name) {
    if (typeof name !== 'string') throw nodeError(TypeError, 'ERR_INVALID_ARG_TYPE', `The "name" argument must be of type string. Received ${typeof name}`);
    return this[kHeaders]?.has(name.toLowerCase()) ?? false;
  }

  removeHeader(name) {
    if (typeof name !== 'string') throw nodeError(TypeError, 'ERR_INVALID_ARG_TYPE', `The "name" argument must be of type string. Received ${typeof name}`);
    if (this._headerSent) throw headersSentError('remove');
    this[kHeaders]?.delete(name.toLowerCase());
  }

  addTrailers(headers) {
    const pairs = [];
    const add = (name, value) => {
      validateHeaderName(name, 'Trailer name');
      validateHeaderValue(name, value);
      for (const v of Array.isArray(value) ? value : [value]) pairs.push(String(name), String(v));
    };
    if (Array.isArray(headers)) {
      for (const entry of headers) add(entry[0], entry[1]);
    } else {
      for (const [name, value] of Object.entries(headers ?? {})) add(name, value);
    }
    this._trailers = (this._trailers ?? []).concat(pairs);
  }

  flushHeaders() {
    if (!this._headerSent) this._implicitHeader();
  }

  setTimeout(ms, callback) {
    if (callback) this.on('timeout', callback);
    this.socket?.setTimeout(ms);
    return this;
  }

  _implicitHeader() {
    throw nodeError(Error, 'ERR_METHOD_NOT_IMPLEMENTED', 'The _implicitHeader() method is not implemented');
  }
}

/** The connection a server's request came on: the client's address, as the ingress saw it. */
class ServerSocket extends EventEmitter {
  constructor(srv, r) {
    super();
    this.server = srv;
    this.remoteAddress = r.remoteAddress ?? undefined;
    this.remotePort = r.remotePort ?? undefined;
    this.remoteFamily = r.remoteAddress === undefined || r.remoteAddress === null ? undefined : r.remoteAddress.includes(':') ? 'IPv6' : 'IPv4';
    if (r.encrypted) this.encrypted = true;
    this.readable = true;
    this.writable = true;
    this.destroyed = false;
    this.bytesRead = 0;
    this.bytesWritten = 0;
    this.timeout = 0;
    this._httpMessage = null;
  }
  get localAddress() { return this.server._address?.address; }
  get localPort() { return this.server._address?.port; }
  address() { return this.server._address ? { ...this.server._address } : {}; }
  setTimeout(ms, callback) { this.timeout = Number(ms) || 0; if (callback) this.once('timeout', callback); return this; }
  setNoDelay() { return this; }
  setKeepAlive() { return this; }
  ref() { return this; }
  unref() { return this; }
  cork() {}
  uncork() {}
  end() { this._httpMessage?.end(); return this; }
  destroy(err) {
    if (this.destroyed) return this;
    this.destroyed = true;
    this.readable = false;
    this.writable = false;
    const res = this._httpMessage;
    if (res && !res.writableFinished) res._abandon();
    nextTick(() => { if (err) this.emit('error', err); this.emit('close', Boolean(err)); });
    return this;
  }
  _clientLeft() {
    if (this.destroyed) return;
    this.destroyed = true;
    this.readable = false;
    this.writable = false;
    nextTick(() => this.emit('close', true));
  }
}

const NO_BODY = (status) => status === 204 || status === 304 || (status >= 100 && status < 200);

export class ServerResponse extends OutgoingMessage {
  constructor(req, options) {
    super(options);
    this.req = req;
    this.statusCode = 200;
    this.statusMessage = undefined;
    this.sendDate = true;
    this._exchange = null;
    this._hasBody = req?.method !== 'HEAD';
    this._fastHeaders = null;
    this._wroteAny = false;
    this._length = null;
    this._clientGone = false;
    if (req?.httpVersionMajor < 1 || (req?.httpVersionMajor === 1 && req?.httpVersionMinor === 0)) this.useChunkedEncodingByDefault = false;
  }

  writeHead(statusCode, reason, obj) {
    if (this._headerSent) throw headersSentError('write');
    const original = statusCode;
    statusCode |= 0;
    if (statusCode < 100 || statusCode > 999) throw nodeError(RangeError, 'ERR_HTTP_INVALID_STATUS_CODE', `Invalid status code: ${original}`);
    if (typeof reason === 'string') {
      this.statusMessage = reason;
    } else {
      this.statusMessage ||= STATUS_CODES[statusCode] || 'unknown';
      obj ??= reason;
    }
    this.statusCode = statusCode;
    if (this[kHeaders]) {
      if (Array.isArray(obj)) {
        if (obj.length % 2 !== 0) throw nodeError(TypeError, 'ERR_INVALID_ARG_VALUE', `The argument 'headers' is invalid. Received ${obj}`);
        for (let n = 0; n < obj.length; n += 2) this.removeHeader(obj[n]);
        for (let n = 0; n < obj.length; n += 2) if (obj[n]) this.appendHeader(obj[n], obj[n + 1]);
      } else if (obj) {
        for (const key of Object.keys(obj)) if (key) this.setHeader(key, obj[key]);
      }
    } else if (obj) {
      // Node's fast path: headers given only here are sent, but getHeader() doesn't see them.
      const pairs = [];
      if (Array.isArray(obj)) {
        if (obj.length % 2 !== 0) throw nodeError(TypeError, 'ERR_INVALID_ARG_VALUE', `The argument 'headers' is invalid. Received ${obj}`);
        for (let n = 0; n < obj.length; n += 2) { validateHeaderName(obj[n]); validateHeaderValue(obj[n], obj[n + 1]); pairs.push([obj[n], obj[n + 1]]); }
      } else {
        for (const key of Object.keys(obj)) { validateHeaderName(key); validateHeaderValue(key, obj[key]); pairs.push([key, obj[key]]); }
      }
      this._fastHeaders = pairs;
    }
    if (INVALID_VALUE.test(this.statusMessage) || /[\r\n]/.test(this.statusMessage)) {
      throw nodeError(TypeError, 'ERR_INVALID_CHAR', 'Invalid character in statusMessage');
    }
    if (NO_BODY(statusCode)) this._hasBody = false;
    this._sendHead();
    return this;
  }

  _implicitHeader() { this.writeHead(this.statusCode); }
  writeContinue(callback) { if (callback) nextTick(callback); }
  writeProcessing(callback) { if (callback) nextTick(callback); }
  writeEarlyHints(hints, callback) { if (callback) nextTick(callback); }
  assignSocket(socket) { this.socket = socket; socket._httpMessage = this; this.emit('socket', socket); }
  detachSocket(socket) { if (socket) socket._httpMessage = null; this.socket = null; }

  /** The status line and headers, to the host: as Node writes them, with Date and the length or chunking it adds. */
  _sendHead() {
    this._headerSent = true;
    const pairs = [];
    const entries = this._fastHeaders ?? [...(this[kHeaders]?.values() ?? [])];
    let hasDate = false;
    let hasLength = false;
    let chunked = false;
    for (const [name, value] of entries) {
      const lower = String(name).toLowerCase();
      if (lower === 'date') hasDate = true;
      if (lower === 'content-length') hasLength = true;
      if (lower === 'transfer-encoding' && /chunked/i.test(String(value))) chunked = true;
      for (const v of Array.isArray(value) ? value : [value]) pairs.push(String(name), String(v));
    }
    if (this.sendDate && !hasDate) pairs.push('Date', new Date().toUTCString());
    if (!hasLength && !chunked && this._hasBody && this._length !== null && !this._trailers) pairs.push('Content-Length', String(this._length));
    this.chunkedEncoding = this._hasBody && !hasLength && (chunked || this._length === null || this._trailers !== null);
    if (this._exchange !== null) server.respond(this._exchange, this.statusCode, this.statusMessage || STATUS_CODES[this.statusCode] || 'unknown', pairs);
  }

  write(chunk, encoding, callback) {
    if (!this._headerSent && !this.writableEnded && chunk !== undefined && chunk !== null) this._wroteAny = true;
    return super.write(chunk, encoding, callback);
  }

  end(chunk, encoding, callback) {
    if (typeof chunk === 'function') { callback = chunk; chunk = undefined; } else if (typeof encoding === 'function') { callback = encoding; encoding = undefined; }
    // Ended in one go, before anything was written: Node sends the length rather than chunks.
    if (!this._headerSent && !this._wroteAny && !this.writableEnded) {
      this._length = chunk === undefined || chunk === null ? 0 : typeof chunk === 'string' ? Buffer.byteLength(chunk, encoding) : chunk.byteLength ?? chunk.length;
    }
    if (chunk === undefined || chunk === null) return super.end(callback);
    return super.end(chunk, encoding, callback);
  }

  _write(chunk, encoding, callback) {
    if (!this._headerSent) {
      this.statusMessage ||= STATUS_CODES[this.statusCode] || 'unknown';
      if (NO_BODY(this.statusCode)) this._hasBody = false;
      this._sendHead();
    }
    if (!this._hasBody || this._clientGone || this._exchange === null || chunk.length === 0) { callback(); return; }
    const bytes = chunk.length;
    server.write(this._exchange, chunk).then(surfaced(() => {
      if (this.socket) this.socket.bytesWritten += bytes;
      callback();
    }), surfaced(() => { this._clientGone = true; callback(); }));
  }

  _final(callback) {
    if (!this._headerSent) {
      this.statusMessage ||= STATUS_CODES[this.statusCode] || 'unknown';
      if (NO_BODY(this.statusCode)) this._hasBody = false;
      this._sendHead();
    }
    if (this._exchange !== null && !this._clientGone) {
      if (this._trailers && this.chunkedEncoding) server.trailers(this._exchange, this._trailers);
      server.end(this._exchange);
    }
    callback();
  }

  /** The socket was destroyed before the response finished: the client's connection is reset. */
  _abandon() {
    if (this._exchange !== null && !this._clientGone) server.fail(this._exchange, 500, 'The server closed the connection');
    this._clientGone = true;
    this.destroy();
  }
}

/** The address a server reports: the host it asked for (an address, or localhost's), else every address (::). */
function boundAddress(host, port) {
  if (host === undefined || host === null || host === '' || host === '::') return { address: '::', family: 'IPv6', port };
  if (host === 'localhost') return { address: '127.0.0.1', family: 'IPv4', port };
  const family = isIPv6Literal(host) ? 'IPv6' : 'IPv4';
  return { address: host, family, port };
}
const isIPv6Literal = (host) => String(host).includes(':');
const WILDCARDS = new Set(['::', '0.0.0.0']);

// The run's ports, as the operating system would see them: two servers can share a port on two specific addresses
// (Fastify listens on localhost's ::1 and 127.0.0.1), and the first one there takes the ingress's requests.
const ports = new Map();

function badPort(port) {
  return nodeError(RangeError, 'ERR_SOCKET_BAD_PORT', `options.port should be >= 0 and < 65536. Received ${typeof port === 'number' ? `type number (${port})` : typeof port === 'string' ? `type string ('${port}')` : String(port)}.`);
}

/** listen()'s arguments, in any of Node's forms: { port, host, path, signal, callback }. */
function listenArgs(args) {
  const out = { port: 0, host: undefined, path: undefined, signal: undefined, callback: undefined };
  if (typeof args[args.length - 1] === 'function') out.callback = args.pop();
  const [first, second] = args;
  if (first !== null && typeof first === 'object') {
    if (first._handle || first.fd !== undefined) throw nodeError(Error, 'ERR_NOT_SUPPORTED', 'Listening on a handle or file descriptor isn\'t available in the isolate: servers are reached by hostname');
    out.port = first.port ?? 0;
    out.host = first.host;
    out.path = first.path;
    out.signal = first.signal;
  } else if (typeof first === 'string' && first !== '' && !/^\d+$/.test(first)) {
    out.path = first;
  } else if (first !== undefined && first !== null) {
    out.port = first;
    if (typeof second === 'string') out.host = second;
  }
  if (out.path !== undefined) {
    throw nodeError(Error, 'ERR_NOT_SUPPORTED', 'Listening on a Unix socket or pipe isn\'t available in the isolate: servers are reached by hostname through the ingress');
  }
  const number = typeof out.port === 'string' && out.port.trim() !== '' ? Number(out.port) : out.port;
  if (typeof number !== 'number' || !Number.isInteger(number) || number < 0 || number > 65535) throw badPort(out.port);
  out.port = number;
  return out;
}

export class Server extends EventEmitter {
  constructor(options, requestListener) {
    super();
    if (typeof options === 'function') { requestListener = options; options = {}; }
    else if (options === null || options === undefined) options = {};
    else if (typeof options !== 'object') throw nodeError(TypeError, 'ERR_INVALID_ARG_TYPE', `The "options" argument must be of type object. Received type ${typeof options} (${options})`);
    this._incomingMessage = options.IncomingMessage ?? IncomingMessage;
    this._serverResponse = options.ServerResponse ?? ServerResponse;
    this.timeout = 0;
    this.keepAliveTimeout = options.keepAliveTimeout ?? 5000;
    this.headersTimeout = options.headersTimeout ?? 60000;
    this.requestTimeout = options.requestTimeout ?? 300000;
    this.maxHeadersCount = null;
    this.maxRequestsPerSocket = 0;
    this.listening = false;
    this._address = null;
    this._stop = null;
    this._starting = false;
    this._primary = false;
    this._responses = new Set();
    this._closing = false;
    this._closeRequested = false;
    // Upgraded connections: the server's 'close' waits for them, as Node's does (D-125).
    this._upgraded = new Set();
    if (options.shouldUpgradeCallback !== undefined) {
      if (typeof options.shouldUpgradeCallback !== 'function') {
        throw nodeError(TypeError, 'ERR_INVALID_ARG_TYPE',
          `The "options.shouldUpgradeCallback" property must be of type function. Received ${describeValue(options.shouldUpgradeCallback)}`);
      }
      this.shouldUpgradeCallback = options.shouldUpgradeCallback;
    } else {
      this.shouldUpgradeCallback = function shouldUpgradeCallback() { return this.listenerCount('upgrade') > 0; };
    }
    if (requestListener) this.on('request', requestListener);
  }

  listen(...args) {
    if (this.listening || this._starting) {
      throw nodeError(Error, 'ERR_SERVER_ALREADY_LISTEN', 'Listen method has been called more than once without closing.');
    }
    const { port: asked, host, signal, callback } = listenArgs(args);
    if (callback) this.once('listening', callback);
    let port = asked;
    if (port === 0) do { port = 1024 + Math.floor(Math.random() * 64512); } while (ports.has(port));
    const address = boundAddress(host, port);
    this._starting = true;
    this._closing = false;
    server.opened();
    const fail = (err) => {
      this._starting = false;
      server.closed();
      nextTick(() => this.emit('error', err));
    };
    const inUse = () => Object.assign(nodeError(Error, 'EADDRINUSE',
      `listen EADDRINUSE: address already in use ${address.family === 'IPv6' ? address.address === '::' ? ':::' : `${address.address}:` : `${address.address}:`}${port}`),
    { errno: -98, syscall: 'listen', address: address.address, port });
    const sharing = ports.get(port);
    if (sharing) {
      if (sharing.has(address.address) || WILDCARDS.has(address.address) || [...sharing].some((a) => WILDCARDS.has(a))) {
        fail(inUse());
        return this;
      }
      // The port is this run's already, on another address: Node lets it be shared, and the ingress has the first.
      sharing.add(address.address);
      this._started(address, null);
      return this;
    }
    ports.set(port, new Set([address.address]));
    server.listen(port, surfaced((exchangeId, r, body) => this._onRequest(exchangeId, r, body))).then(surfaced((stop) => {
      if (this._closing) {
        // Closed while it was starting: it never listened.
        stop();
        ports.delete(port);
        server.closed();
        this._starting = false;
        this._closing = false;
        this._closeIfDrained();
        return;
      }
      this._started(address, stop);
    }), surfaced((err) => {
      ports.delete(port);
      fail(err?.code === 'EADDRINUSE' ? inUse()
        : nodeError(Error, err?.code === 'not_a_service' ? 'ERR_NOT_SUPPORTED' : (err?.code ?? 'ERR_SERVER_NOT_RUNNING'), String(err?.message ?? err)));
    }));
    if (signal) {
      const onAbort = () => this.close();
      if (signal.aborted) nextTick(onAbort); else signal.addEventListener('abort', onAbort, { once: true });
    }
    return this;
  }

  _started(address, stop) {
    this._starting = false;
    this._address = address;
    this._stop = stop;
    this._primary = stop !== null;
    this.listening = true;
    nextTick(() => this.emit('listening'));
  }

  _release() {
    if (!this._address) return;
    const sharing = ports.get(this._address.port);
    sharing?.delete(this._address.address);
    if (sharing && sharing.size === 0) ports.delete(this._address.port);
  }

  address() { return this.listening ? { ...this._address } : null; }

  close(callback) {
    this._closeRequested = true;
    if (typeof callback === 'function') {
      if (!this.listening && !this._starting) {
        this.once('close', () => callback(nodeError(Error, 'ERR_SERVER_NOT_RUNNING', 'Server is not running.')));
      } else {
        this.once('close', callback);
      }
    }
    if (this._starting) { this._closing = true; return this; }
    if (this.listening) {
      this.listening = false;
      this._stop?.();
      this._stop = null;
      this._release();
      server.closed();
    }
    this.closeIdleConnections();
    this._closeIfDrained();
    return this;
  }

  /** 'close', as Node's: once a close was asked for and no response is in flight. */
  _closeIfDrained() {
    if (!this._closeRequested || this.listening || this._starting || this._responses.size > 0 || this._upgraded.size > 0) return;
    this._closeRequested = false;
    nextTick(() => this.emit('close'));
  }

  closeAllConnections() {
    for (const res of [...this._responses]) res.socket?.destroy();
  }

  closeIdleConnections() {}

  setTimeout(ms, callback) {
    this.timeout = Number(ms) || 0;
    if (callback) this.on('timeout', callback);
    return this;
  }

  ref() { return this; }
  unref() { return this; }
  getConnections(callback) { nextTick(callback, null, this._responses.size); return this; }
  [Symbol.asyncDispose]() { return new Promise((resolve) => this.close(() => resolve())); }

  /**
   * Hands an upgrading request's connection to the 'upgrade' listener as a net.Socket (D-125), with no data read past
   * the head (it comes as the socket's data). False if the host can't take the connection over.
   */
  _upgrade(exchangeId, r, req, standIn) {
    const taken = server.upgrade(exchangeId);
    if (!taken) return false;
    // The request has no body to read: what the client sends next is the upgraded protocol's.
    try { req._source.cancel(); } catch { /* finished */ }
    req._body = (async function* nothing() {})();
    req.complete = true;
    const socket = new NetSocket({ allowHalfOpen: true });
    socket._adopt(taken.id, taken.body, {
      remoteAddress: standIn.remoteAddress, remotePort: standIn.remotePort, remoteFamily: standIn.remoteFamily,
      localAddress: this._address?.address, localPort: this._address?.port, localFamily: this._address?.family,
    });
    socket.server = this;
    if (r.encrypted) socket.encrypted = true;
    req.socket = socket;
    this._upgraded.add(socket);
    socket.once('close', () => { this._upgraded.delete(socket); this._closeIfDrained(); });
    if (this.listenerCount('upgrade') > 0) this.emit('upgrade', req, socket, Buffer.alloc(0));
    else socket.destroy();
    return true;
  }

  _onRequest(exchangeId, r, body) {
    const socket = new ServerSocket(this, r);
    const req = new this._incomingMessage(socket);
    req.method = r.method;
    req.url = r.uri ?? r.path;
    req.httpVersion = r.httpVersion ?? '1.1';
    req.httpVersionMajor = Number(req.httpVersion.split('.')[0]);
    req.httpVersionMinor = Number(req.httpVersion.split('.')[1] ?? 0);
    req.rawHeaders = (r.rawHeaders ?? []).map(String);
    const { headers, distinct } = headersOf(req.rawHeaders);
    req.headers = headers;
    req.headersDistinct = distinct;
    req._source = body;
    req._body = body[Symbol.asyncIterator]();
    // As llhttp, then the server, decide it: the 'upgrade' listener (or shouldUpgradeCallback) takes the connection.
    req.upgrade = Boolean(r.upgrade) && Boolean(this.shouldUpgradeCallback(req));
    if (req.upgrade && this._upgrade(exchangeId, r, req, socket)) return;
    req.upgrade = false;
    const res = new this._serverResponse(req);
    res._exchange = exchangeId;
    res.shouldKeepAlive = true;
    res.assignSocket(socket);
    this._responses.add(res);
    const done = () => {
      if (!this._responses.delete(res)) return;
      // Whatever of the body the handler didn't read is let go, as Node dumps it.
      if (!req.readableEnded) { try { req._source.cancel(); } catch { /* finished */ } }
      this._closeIfDrained();
    };
    res.on('finish', done);
    res.on('close', done);
    server.clientClosed(exchangeId).then(surfaced((left) => {
      if (!left) return;
      res._clientGone = true;
      if (!req.complete) { req.aborted = true; req.destroy(); }
      socket._clientLeft();
      if (!res.writableFinished) res.destroy();
    }), () => {});
    this.emit('request', req, res);
  }
}

/** The Node type description in ERR_INVALID_ARG_TYPE messages, for a value that isn't the expected type. */
function describeValue(value) {
  if (value === null) return 'null';
  if (value === undefined) return 'undefined';
  if (typeof value === 'function') return `function ${value.name}`;
  if (typeof value === 'object') return value.constructor?.name ? `an instance of ${value.constructor.name}` : 'an object';
  const shown = typeof value === 'string' ? `'${value.length > 28 ? `${value.slice(0, 25)}...` : value}'` : String(value);
  return `type ${typeof value} (${shown})`;
}

export function createServer(options, requestListener) {
  return new Server(options, requestListener);
}

export function setMaxIdleHTTPParsers() {}

export default {
  METHODS, STATUS_CODES, maxHeaderSize, Agent, globalAgent, ClientRequest, IncomingMessage, OutgoingMessage, ServerResponse, Server,
  request, get, createServer, validateHeaderName, validateHeaderValue, setMaxIdleHTTPParsers,
};
