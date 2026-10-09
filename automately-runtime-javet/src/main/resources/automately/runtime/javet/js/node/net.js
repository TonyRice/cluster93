// Node's `net` in the isolate (D-109, D-116): address checks, and outbound TCP sockets on the host, through the egress
// gate (as `tcp`) and the cluster policy's limits. A socket connects to the exact address the gate checked. There's no
// idle timeout, as in Node; a socket closes when it's closed or when its run ends. Servers never run on the cluster's
// nodes: inbound traffic is HTTP by hostname (D-112).
import { EventEmitter } from 'node:events';
import { Duplex } from 'node:stream';
import { Buffer } from 'node:buffer';
import { nextTick } from 'node:process';

const internals = globalThis[Symbol.for('automately.sockets')];
const reportUncaught = globalThis[Symbol.for('automately.uncaught')];
/** Runs host-callback work; a throw from a listener it calls ends the job, as in Node, instead of vanishing. */
const surfaced = (fn) => (...args) => { try { return fn(...args); } catch (e) { reportUncaught(e, 'a net socket event listener'); return undefined; } };

// Node's own patterns (lib/internal/net.js).
const v4Seg = '(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]|[0-9])';
const v4Str = `(?:${v4Seg}\\.){3}${v4Seg}`;
const IPv4Reg = new RegExp(`^${v4Str}$`);
const v6Seg = '(?:[0-9a-fA-F]{1,4})';
const IPv6Reg = new RegExp('^(?:' +
  `(?:${v6Seg}:){7}(?:${v6Seg}|:)|` +
  `(?:${v6Seg}:){6}(?:${v4Str}|:${v6Seg}|:)|` +
  `(?:${v6Seg}:){5}(?::${v4Str}|(?::${v6Seg}){1,2}|:)|` +
  `(?:${v6Seg}:){4}(?:(?::${v6Seg}){0,1}:${v4Str}|(?::${v6Seg}){1,3}|:)|` +
  `(?:${v6Seg}:){3}(?:(?::${v6Seg}){0,2}:${v4Str}|(?::${v6Seg}){1,4}|:)|` +
  `(?:${v6Seg}:){2}(?:(?::${v6Seg}){0,3}:${v4Str}|(?::${v6Seg}){1,5}|:)|` +
  `(?:${v6Seg}:){1}(?:(?::${v6Seg}){0,4}:${v4Str}|(?::${v6Seg}){1,6}|:)|` +
  `(?::(?:(?::${v6Seg}){0,5}:${v4Str}|(?::${v6Seg}){1,7}|:))` +
')(?:%[0-9a-zA-Z-.:]{1,})?$');

export function isIPv4(input) { return IPv4Reg.test(`${input}`); }
export function isIPv6(input) { return IPv6Reg.test(`${input}`); }
export function isIP(input) { return isIPv4(input) ? 4 : isIPv6(input) ? 6 : 0; }

function nodeError(Type, code, message, extra) {
  const err = new Type(message);
  err.code = code;
  return extra ? Object.assign(err, extra) : err;
}

const ERRNO = { ECONNREFUSED: -111, ETIMEDOUT: -110, ECONNRESET: -104, EPIPE: -32, EHOSTUNREACH: -113, ENOTFOUND: -3008 };

/** A host failure as Node's error: its code, Node's message (the host writes it), and the fields Node sets. */
export function socketError(err, host, port) {
  if (!err || typeof err !== 'object') return nodeError(Error, 'ECONNRESET', String(err));
  let code = err.code;
  let message = String(err.message ?? err);
  const extra = {};
  if (code === 'dns_failed') {
    code = 'ENOTFOUND';
    message = `getaddrinfo ENOTFOUND ${host}`;
    Object.assign(extra, { syscall: 'getaddrinfo', hostname: host });
  } else if (code === 'ECONNREFUSED' || code === 'ETIMEDOUT' || code === 'EHOSTUNREACH') {
    const at = /\s(\[?[0-9a-fA-F.:]+\]?):(\d+)$/.exec(message);
    Object.assign(extra, { syscall: 'connect', address: at ? at[1].replace(/^\[|\]$/g, '') : host, port: at ? Number(at[2]) : port });
  } else if (code === 'ECONNRESET') {
    extra.syscall = 'read';
  } else if (code === 'EPIPE') {
    extra.syscall = 'write';
  }
  if (ERRNO[code] !== undefined) extra.errno = ERRNO[code];
  const Type = code === 'ERR_SOCKET_BAD_PORT' ? RangeError : code === 'ERR_INVALID_ARG_VALUE' ? TypeError : Error;
  return nodeError(Type, code, message, extra);
}

function unsupported(what, why) {
  return nodeError(Error, 'ERR_NOT_SUPPORTED', `${what} isn't available in the isolate: ${why}`);
}
const NO_SERVERS = "TCP servers don't run on the cluster's nodes, where inbound traffic is HTTP by hostname; they run on an Automately link's own host";

/** Reads a host stream for whichever socket owns it now: a TLS upgrade hands it to the new TLSSocket mid-read. */
class Connection {
  constructor(id, body, owner) {
    this.id = id;
    this.body = body;
    this.iterator = body[Symbol.asyncIterator]();
    this.owner = owner;
    this.pulling = false;
    this.done = false;
  }
  pull() {
    if (this.pulling || this.done) return;
    this.pulling = true;
    this.iterator.next().then(surfaced(({ value, done }) => {
      this.pulling = false;
      if (done) { this.done = true; this.owner._onEnd(); } else this.owner._onData(value);
    }), surfaced((err) => {
      this.pulling = false;
      this.done = true;
      this.owner._onError(err);
    }));
  }
  close() {
    this.done = true;
    this.body.cancel();
  }
}

/** Node's argument forms: (options[, cb]), (port[, host][, cb]), (path[, cb]). */
export function normalizeArgs(args) {
  let options = {};
  const first = args[0];
  if (first !== null && typeof first === 'object') options = { ...first };
  else if (typeof first === 'string' && !/^\d+$/.test(first)) options.path = first;
  else {
    options.port = first;
    if (typeof args[1] === 'string') options.host = args[1];
  }
  const callback = args[args.length - 1];
  return [options, typeof callback === 'function' ? callback : null];
}

function validPort(port) {
  const n = typeof port === 'string' && port.trim() !== '' ? Number(port) : port;
  if (typeof n !== 'number' || !Number.isInteger(n) || n < 0 || n > 65535) {
    const received = typeof port === 'string' ? `type string ('${port}')` : typeof port === 'number' ? `type number (${port})`
      : port === null ? 'null' : port === undefined ? 'undefined' : `type ${typeof port}`;
    throw nodeError(RangeError, 'ERR_SOCKET_BAD_PORT', `Port should be >= 0 and < 65536. Received ${received}.`);
  }
  return n;
}

export class Socket extends Duplex {
  constructor(options = {}) {
    super({ allowHalfOpen: Boolean(options?.allowHalfOpen), emitClose: false, highWaterMark: options?.highWaterMark });
    this.connecting = false;
    this.pending = true;
    this._addresses = null;
    this.bytesRead = 0;
    this.bytesWritten = 0;
    this.timeout = 0;
    this._id = null;
    this._conn = null;
    this._host = undefined;
    this._port = undefined;
    this._waiting = [];
    this._timer = null;
    this._noDelay = options?.noDelay ?? true;
    this._keepAlive = Boolean(options?.keepAlive);
    this._tlsOptions = null;
    if (options?.signal) {
      const onAbort = () => this.destroy(nodeError(Error, 'ABORT_ERR', 'The operation was aborted', { name: 'AbortError' }));
      if (options.signal.aborted) nextTick(onAbort);
      else options.signal.addEventListener('abort', onAbort, { once: true });
    }
  }

  // Node's getter: an unconnected socket is readable and writable, so it reads 'open'.
  get readyState() {
    if (this.connecting) return 'opening';
    if (this.readable && this.writable) return 'open';
    return this.readable ? 'readOnly' : this.writable ? 'writeOnly' : 'closed';
  }

  get bufferSize() { return this.writableLength; }

  connect(...args) {
    const [options, callback] = normalizeArgs(args);
    if (options.path !== undefined) throw unsupported('Connecting to a Unix socket', 'the isolate reaches only TCP addresses');
    if (this.connecting || this._id !== null) {
      throw nodeError(Error, 'ERR_SOCKET_CONNECTING', 'Socket is already connecting');
    }
    const port = validPort(options.port);
    const host = options.host ?? 'localhost';
    if (options.noDelay !== undefined) this._noDelay = Boolean(options.noDelay);
    if (options.keepAlive !== undefined) this._keepAlive = Boolean(options.keepAlive);
    if (options.timeout !== undefined) this.setTimeout(options.timeout);
    this.connecting = true;
    this._host = host;
    this._port = port;
    if (callback) this.once(this._tlsOptions ? 'secureConnect' : 'connect', callback);
    const request = { host, port };
    if (this._tlsOptions) request.tls = this._tlsOptions;
    internals.connect(request).then(surfaced(({ id, info, body }) => {
      if (this.destroyed) { internals.destroy(id); body.cancel(); return; }
      this._id = id;
      this._conn = new Connection(id, body, this);
      if (!this._noDelay) internals.setNoDelay(id, false);
      if (this._keepAlive) internals.setKeepAlive(id, true);
      this._connected(info);
    }), surfaced((err) => this.destroy(socketError(err, host, port))));
    return this;
  }

  /** The host connected: take on its addresses, start what was waiting, and announce it in Node's order. */
  _connected(info) {
    this.connecting = false;
    this.pending = false;
    this._describe(info);
    if (!isIP(this._host) && this.remoteAddress) this.emit('lookup', null, this.remoteAddress, this.remoteFamily === 'IPv6' ? 6 : 4, this._host);
    this.emit('connect');
    if (this._secured(info)) {
      this.emit('ready');
      this._flush();
    }
  }

  _describe(info) {
    this._addresses = { remoteAddress: info.remoteAddress, remotePort: info.remotePort, remoteFamily: info.remoteFamily,
      localAddress: info.localAddress, localPort: info.localPort, localFamily: info.localFamily };
  }

  /**
   * A connection the host already has, open (D-125): an upgraded request's, which an http server hands its 'upgrade'
   * listener. There's nothing to announce; it reads and writes as any connected socket.
   */
  _adopt(id, body, info) {
    this._id = id;
    this._conn = new Connection(id, body, this);
    this.connecting = false;
    this.pending = false;
    this._describe(info);
    this._flush();
    return this;
  }

  // As Node's, read from the live connection: undefined before it connects and once it's closed.
  get remoteAddress() { return this.destroyed ? undefined : this._addresses?.remoteAddress; }
  get remotePort() { return this.destroyed ? undefined : this._addresses?.remotePort; }
  get remoteFamily() { return this.destroyed ? undefined : this._addresses?.remoteFamily; }
  get localAddress() { return this.destroyed ? undefined : this._addresses?.localAddress; }
  get localPort() { return this.destroyed ? undefined : this._addresses?.localPort; }
  get localFamily() { return this.destroyed ? undefined : this._addresses?.localFamily; }

  /** For TLSSocket: check the peer and emit secureConnect; false if the connection was refused. */
  _secured() { return true; }

  _flush() {
    const waiting = this._waiting;
    this._waiting = [];
    for (const go of waiting) go();
    if (this._wantsRead) { this._wantsRead = false; this._conn?.pull(); }
    this._touch();
  }

  /** Runs `go` once connected (and secured); writes before that wait, as Node buffers them. */
  _whenConnected(go) {
    if (this._id !== null && !this.connecting && !this._securing) go();
    else this._waiting.push(go);
  }

  _read() {
    if (this._conn && !this.connecting && !this._securing) this._conn.pull();
    else this._wantsRead = true;
  }

  _onData(bytes) {
    const chunk = Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    this.bytesRead += chunk.length;
    this._touch();
    this.push(chunk);
  }

  _onEnd() {
    this.push(null);
  }

  _onError(err) {
    this.destroy(socketError(err, this._host, this._port));
  }

  _write(chunk, encoding, callback) {
    this._whenConnected(() => {
      if (this._id === null) { callback(nodeError(Error, 'EPIPE', 'This socket has been ended by the other party', { syscall: 'write' })); return; }
      internals.write(this._id, chunk).then(surfaced(() => {
        this.bytesWritten += chunk.length;
        this._touch();
        callback();
      }), surfaced((err) => callback(socketError(err, this._host, this._port))));
    });
  }

  _final(callback) {
    this._whenConnected(() => {
      if (this._id === null) { callback(); return; }
      internals.end(this._id).then(surfaced(() => callback()), surfaced((err) => callback(socketError(err, this._host, this._port))));
    });
  }

  _destroy(err, callback) {
    this._clearTimer();
    this.connecting = false;
    if (this._id !== null && !this._handedOver) internals.destroy(this._id);
    if (this._conn && !this._handedOver) this._conn.close();
    // Writes still waiting for a connection that won't come fail with the socket.
    for (const go of this._waiting.splice(0)) { try { go(); } catch { /* the stream is already ending */ } }
    callback(err);
    nextTick(() => this.emit('close', Boolean(err)));
  }

  setTimeout(ms, callback) {
    this.timeout = Number(ms) || 0;
    if (callback) {
      if (this.timeout === 0) this.removeListener('timeout', callback);
      else this.once('timeout', callback);
    }
    this._touch();
    return this;
  }

  _touch() {
    this._clearTimer();
    if (this.timeout > 0 && !this.destroyed) {
      // Node's idle timeout: it only tells the socket; closing is the listener's choice.
      this._timer = setTimeout(() => { this._timer = null; this.emit('timeout'); }, this.timeout);
    }
  }

  _clearTimer() {
    if (this._timer) { clearTimeout(this._timer); this._timer = null; }
  }

  setNoDelay(noDelay = true) {
    this._noDelay = Boolean(noDelay);
    if (this._id !== null) internals.setNoDelay(this._id, this._noDelay);
    return this;
  }

  setKeepAlive(enable = false) {
    this._keepAlive = Boolean(enable);
    if (this._id !== null) internals.setKeepAlive(this._id, this._keepAlive);
    return this;
  }

  address() {
    return this.localAddress === undefined ? {} : { address: this.localAddress, family: this.localFamily, port: this.localPort };
  }

  ref() { return this; }
  unref() { return this; }

  destroySoon() {
    if (this.writable) this.end();
    if (this.writableFinished) this.destroy();
    else this.once('finish', () => this.destroy());
  }

  resetAndDestroy() {
    return this.destroy(nodeError(Error, 'ERR_SOCKET_CLOSED', 'Socket is closed'));
  }
}
export const Stream = Socket;

export function connect(...args) {
  const [options] = normalizeArgs(args);
  const socket = new Socket(options);
  return socket.connect(...args);
}
export const createConnection = connect;

export class Server extends EventEmitter {
  constructor() { super(); throw unsupported('net.Server', NO_SERVERS); }
}
export function createServer() { throw unsupported('net.createServer', NO_SERVERS); }

let autoSelectFamily = true;
export function getDefaultAutoSelectFamily() { return autoSelectFamily; }
export function setDefaultAutoSelectFamily(value) { autoSelectFamily = Boolean(value); }
let autoSelectFamilyAttemptTimeout = 250;
export function getDefaultAutoSelectFamilyAttemptTimeout() { return autoSelectFamilyAttemptTimeout; }
export function setDefaultAutoSelectFamilyAttemptTimeout(value) { autoSelectFamilyAttemptTimeout = Math.max(10, Number(value) || 250); }

export default { isIP, isIPv4, isIPv6, Socket, Stream, Server, connect, createConnection, createServer,
  getDefaultAutoSelectFamily, setDefaultAutoSelectFamily, getDefaultAutoSelectFamilyAttemptTimeout,
  setDefaultAutoSelectFamilyAttemptTimeout };
