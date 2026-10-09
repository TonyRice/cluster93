// A stand-in for Node's `http` (D-109): the client, on a host request whose body streams, through the same egress gate
// as fetch (D-108). No sockets: the host connects to the address the gate checked, and `req.socket` is a stand-in
// with the methods libraries call. node:https is this module with TLS (js/node/https.js).
import { Buffer } from 'node:buffer';
import { EventEmitter } from 'node:events';
import { Readable, Writable } from 'node:stream';
import { nextTick } from 'node:process';
import { checkServerIdentity as defaultIdentityCheck, peerCertificate } from 'node:tls';

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
  createConnection: 'createConnection would bypass the egress gate',
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
    this._wroteBody = true;
    const bytes = chunk.length;
    this._open(!this.hasHeader('content-length')).then((id) => internals.write(id, chunk)).then(surfaced(() => {
      this.socket.bytesWritten += bytes;
      this._touch();
      callback();
    }), surfaced((err) => callback(networkError(err, this))));
  }

  _final(callback) {
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
    if (this._done) { callback(err); return; }
    if (this._id !== null) internals.abort(this._id);
    if (this.res && !this.res.readableEnded) this.res.destroy(err ?? this._closedError());
    // Destroyed before its response, a request fails with "socket hang up", as Node's does; abort() doesn't fail it.
    callback(err ?? (this.res || this.aborted ? null : this._closedError()));
  }
}

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

const HTTP = { protocol: 'http:', defaultPort: 80, tls: false, get agent() { return globalAgent; } };

export function request(input, options, callback) {
  return new ClientRequest(input, options, callback, HTTP);
}

export function get(input, options, callback) {
  const req = request(input, options, callback);
  req.end();
  return req;
}

// --- servers: phase B3 ---

export function createServer() {
  throw nodeError(Error, 'ERR_NOT_SUPPORTED', "http.createServer isn't available in the isolate yet; use ctx.serve in a service");
}
export class Server extends EventEmitter {
  constructor() { super(); throw nodeError(Error, 'ERR_NOT_SUPPORTED', "http.Server isn't available in the isolate yet; use ctx.serve in a service"); }
}
export class OutgoingMessage extends Writable {}
export class ServerResponse extends OutgoingMessage {}

export function setMaxIdleHTTPParsers() {}

export default {
  METHODS, STATUS_CODES, maxHeaderSize, Agent, globalAgent, ClientRequest, IncomingMessage, OutgoingMessage, ServerResponse, Server,
  request, get, createServer, validateHeaderName, validateHeaderValue, setMaxIdleHTTPParsers,
};
