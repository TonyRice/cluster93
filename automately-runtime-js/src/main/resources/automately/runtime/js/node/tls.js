// Node's `tls` in the isolate (D-109, D-116): TLS sockets on the host, through the egress gate as `tcp`, verified
// against the JVM's trust store (or `ca`) with Node's rules for opting out. `tls.connect({ socket })` upgrades a
// connected net.Socket in place (Postgres's SSLRequest, SMTP's STARTTLS). Servers never run on the cluster's nodes.
import { EventEmitter } from 'node:events';
import { Buffer } from 'node:buffer';
import { nextTick } from 'node:process';
import { Socket, normalizeArgs, socketError, isIP } from 'node:net';

const internals = globalThis[Symbol.for('automately.sockets')];
const reportUncaught = globalThis[Symbol.for('automately.uncaught')];
const surfaced = (fn) => (...args) => { try { return fn(...args); } catch (e) { reportUncaught(e, 'a tls socket event listener'); return undefined; } };

function nodeError(Type, code, message, extra) {
  const err = new Type(message);
  err.code = code;
  return extra ? Object.assign(err, extra) : err;
}

function unsupported(what, why) {
  return nodeError(Error, 'ERR_NOT_SUPPORTED', `${what} isn't available in the isolate: ${why}`);
}
const NO_SERVERS = "TLS servers don't run on the cluster's nodes, where inbound traffic is HTTP by hostname; they run on an Automately link's own host";

export const DEFAULT_ECDH_CURVE = 'auto';
export const DEFAULT_MIN_VERSION = 'TLSv1.2';
export const DEFAULT_MAX_VERSION = 'TLSv1.3';
export const DEFAULT_CIPHERS = 'TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256';

/** The certificates the host trusts (the JVM's trust store), as PEM; read from the host the first time it's used. */
let roots = null;
const loadRoots = () => (roots ??= Object.freeze(internals.rootCertificates()));
export const rootCertificates = new Proxy([], {
  get: (target, key) => Reflect.get(loadRoots(), key),
  has: (target, key) => Reflect.has(loadRoots(), key),
  ownKeys: () => Reflect.ownKeys(loadRoots()),
  getOwnPropertyDescriptor: (target, key) => {
    const descriptor = Reflect.getOwnPropertyDescriptor(loadRoots(), key);
    return descriptor && key !== 'length' ? { ...descriptor, configurable: true } : descriptor;
  },
  set: () => false,
  defineProperty: () => false,
  deleteProperty: () => false,
});

// --- checkServerIdentity: Node's algorithm (lib/tls.js) ---

function unfqdn(host) { return host.replace(/[.]$/, ''); }
function splitHost(host) { return unfqdn(host).toLowerCase().split('.'); }

function check(hostParts, pattern, wildcards) {
  if (!pattern) return false;
  const patternParts = splitHost(pattern);
  if (hostParts.length !== patternParts.length) return false;
  if (patternParts.includes('')) return false;
  const isBad = (s) => /[^!-\u007F]/u.test(s);
  if (patternParts.some(isBad)) return false;
  for (let i = hostParts.length - 1; i > 0; i -= 1) {
    if (hostParts[i] !== patternParts[i]) return false;
  }
  const hostSubdomain = hostParts[0];
  const patternSubdomain = patternParts[0];
  const patternSubdomainParts = patternSubdomain.split('*');
  if (patternSubdomainParts.length === 1 || patternSubdomain.includes('xn--')) return hostSubdomain === patternSubdomain;
  if (!wildcards) return false;
  if (patternSubdomainParts.length > 2) return false;
  if (patternParts.length <= 2) return false;
  const [prefix, suffix] = patternSubdomainParts;
  if (prefix.length + suffix.length > hostSubdomain.length) return false;
  if (!hostSubdomain.startsWith(prefix)) return false;
  if (!hostSubdomain.endsWith(suffix)) return false;
  return true;
}

const jsonStringPattern = /^"(?:[^"\\\u0000-\u001f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"/;
function splitEscapedAltNames(altNames) {
  const result = [];
  let currentToken = '';
  let offset = 0;
  while (offset !== altNames.length) {
    const nextSep = altNames.indexOf(',', offset);
    const nextQuote = altNames.indexOf('"', offset);
    if (nextQuote !== -1 && (nextSep === -1 || nextQuote < nextSep)) {
      currentToken += altNames.substring(offset, nextQuote);
      const match = jsonStringPattern.exec(altNames.substring(nextQuote));
      if (!match) throw nodeError(Error, 'ERR_TLS_CERT_ALTNAME_FORMAT', 'Invalid subject alternative name string');
      currentToken += JSON.parse(match[0]);
      offset = nextQuote + match[0].length;
    } else if (nextSep !== -1) {
      currentToken += altNames.substring(offset, nextSep);
      result.push(currentToken);
      currentToken = '';
      offset = nextSep + 2;
    } else {
      currentToken += altNames.substring(offset);
      offset = altNames.length;
    }
  }
  result.push(currentToken);
  return result;
}

/** Whether `cert` is for `hostname`, as Node decides it; an Error with code ERR_TLS_CERT_ALTNAME_INVALID if not. */
export function checkServerIdentity(hostname, cert) {
  const subject = cert?.subject;
  const altNames = cert?.subjectaltname;
  const dnsNames = [];
  const ips = [];
  hostname = '' + hostname;
  if (altNames) {
    const splitAltNames = altNames.includes('"') ? splitEscapedAltNames(altNames) : altNames.split(', ');
    for (const name of splitAltNames) {
      if (name.startsWith('DNS:')) dnsNames.push(name.slice(4));
      else if (name.startsWith('IP Address:')) ips.push(canonicalizeIP(name.slice(11)));
    }
  }
  let valid = false;
  let reason = 'Unknown reason';
  hostname = unfqdn(hostname);
  if (isIP(hostname)) {
    valid = ips.includes(canonicalizeIP(hostname));
    if (!valid) reason = `IP: ${hostname} is not in the cert's list: ` + ips.join(', ');
  } else if (dnsNames.length > 0 || subject?.CN) {
    const hostParts = splitHost(hostname);
    const wildcard = (pattern) => check(hostParts, pattern, true);
    if (dnsNames.length > 0) {
      valid = dnsNames.some(wildcard);
      if (!valid) reason = `Host: ${hostname}. is not in the cert's altnames: ${altNames}`;
    } else {
      const cn = subject.CN;
      if (Array.isArray(cn)) valid = cn.some(wildcard);
      else if (cn) valid = wildcard(cn);
      if (!valid) reason = `Host: ${hostname}. is not cert's CN: ${cn}`;
    }
  } else {
    reason = 'Cert does not contain a DNS name';
  }
  if (!valid) {
    return nodeError(Error, 'ERR_TLS_CERT_ALTNAME_INVALID', `Hostname/IP does not match certificate's altnames: ${reason}`,
      { reason, host: hostname, cert });
  }
  return undefined;
}

/** An address in one canonical form, as Node's inet_pton/inet_ntop round trip gives: IPv6 compressed and lower case. */
function canonicalizeIP(ip) {
  if (!ip.includes(':')) return ip;
  let text = ip.toLowerCase().replace(/%.*$/, '');
  // An embedded IPv4 tail becomes two groups.
  const v4 = /(\d+)\.(\d+)\.(\d+)\.(\d+)$/.exec(text);
  if (v4) {
    const [a, b, c, d] = v4.slice(1).map(Number);
    text = text.slice(0, v4.index) + ((a << 8) | b).toString(16) + ':' + ((c << 8) | d).toString(16);
  }
  const halves = text.split('::');
  if (halves.length > 2) return ip;
  const head = halves[0] ? halves[0].split(':') : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(':') : [];
  const missing = 8 - head.length - tail.length;
  if (missing < 0 || (halves.length === 1 && missing !== 0)) return ip;
  const groups = [...head, ...Array(halves.length === 2 ? missing : 0).fill('0'), ...tail].map((g) => parseInt(g, 16).toString(16));
  // RFC 5952: the longest run of two or more zero groups (the first, if tied) becomes "::".
  let best = -1;
  let bestLength = 1;
  for (let i = 0; i < 8;) {
    if (groups[i] !== '0') { i++; continue; }
    let j = i;
    while (j < 8 && groups[j] === '0') j++;
    if (j - i > bestLength) { best = i; bestLength = j - i; }
    i = j;
  }
  if (best < 0) return groups.join(':');
  return groups.slice(0, best).join(':') + '::' + groups.slice(best + bestLength).join(':');
}

// --- secure contexts ---

const pem = (value) => (value === undefined || value === null ? undefined
  : Array.isArray(value) ? value.map(pem) : typeof value === 'string' ? value : Buffer.from(value).toString('utf8'));

export class SecureContext {
  constructor(options = {}) {
    if (options.pfx !== undefined) throw unsupported('A PFX bundle', 'pass PEM cert and key');
    if (options.passphrase !== undefined) throw unsupported('An encrypted private key', 'pass an unencrypted PEM key');
    this.context = { ca: pem(options.ca), cert: pem(Array.isArray(options.cert) ? options.cert[0] : options.cert),
      key: pem(Array.isArray(options.key) ? options.key[0] : options.key) };
  }
}
export function createSecureContext(options = {}) {
  return new SecureContext(options);
}

/** ALPN as Node takes it: a list of strings, or the wire format (length-prefixed) as a Buffer or Uint8Array. */
function alpnList(value) {
  if (value === undefined || value === null) return undefined;
  if (Array.isArray(value)) return value.map((p) => (typeof p === 'string' ? p : Buffer.from(p).toString('latin1')));
  const bytes = Buffer.from(value);
  const out = [];
  for (let i = 0; i < bytes.length;) { const n = bytes[i]; out.push(bytes.subarray(i + 1, i + 1 + n).toString('latin1')); i += 1 + n; }
  return out;
}

let warnedInsecure = false;

/** The options the host honours, from tls.connect's; null fields left out. */
function hostOptions(options, host) {
  if (options.pfx !== undefined) throw unsupported('A PFX bundle', 'pass PEM cert and key');
  if (options.passphrase !== undefined) throw unsupported('An encrypted private key', 'pass an unencrypted PEM key');
  const context = options.secureContext?.context ?? {};
  let reject = options.rejectUnauthorized;
  if (reject === undefined && globalThis.process?.env?.NODE_TLS_REJECT_UNAUTHORIZED === '0') reject = false;
  const out = {};
  if (reject === false) {
    out.rejectUnauthorized = false;
    if (!warnedInsecure) {
      warnedInsecure = true;
      console.warn('tls: certificate verification is off for a connection (rejectUnauthorized: false); only the job itself is affected');
    }
  }
  const ca = pem(options.ca) ?? context.ca;
  const cert = pem(Array.isArray(options.cert) ? options.cert[0] : options.cert) ?? context.cert;
  const key = pem(Array.isArray(options.key) ? options.key[0] : options.key) ?? context.key;
  if (ca !== undefined) out.ca = ca;
  if (cert !== undefined) out.cert = cert;
  if (key !== undefined) out.key = key;
  const servername = options.servername ?? (host && !isIP(host) ? host : undefined);
  if (servername) out.servername = servername;
  const alpn = alpnList(options.ALPNProtocols);
  if (alpn?.length) out.ALPNProtocols = alpn;
  if (options.minVersion) out.minVersion = options.minVersion;
  if (options.maxVersion) out.maxVersion = options.maxVersion;
  return out;
}

/** The chain the host described, in getPeerCertificate's shape: raw and pubkey as Buffers, issuers linked if `detailed`. */
export function peerCertificate(tls, detailed) {
  // Node's keys in Node's order, undefined where they don't apply (lib's X509ToObject).
  const chain = (tls?.peerCertificates ?? []).map((c) => {
    const rsa = c.modulus !== undefined;
    const cert = { subject: c.subject, issuer: c.issuer, subjectaltname: c.subjectaltname, infoAccess: c.infoAccess, ca: c.ca };
    if (rsa) Object.assign(cert, { modulus: c.modulus, exponent: c.exponent });
    Object.assign(cert, { pubkey: c.pubkey === undefined ? undefined : Buffer.from(c.pubkey, 'base64'), bits: c.bits,
      valid_from: c.valid_from, valid_to: c.valid_to, fingerprint: c.fingerprint, fingerprint256: c.fingerprint256,
      fingerprint512: c.fingerprint512, ext_key_usage: c.ext_key_usage, serialNumber: c.serialNumber,
      raw: c.raw === undefined ? undefined : Buffer.from(c.raw, 'base64'), asn1Curve: c.asn1Curve, nistCurve: c.nistCurve });
    return cert;
  });
  if (!chain.length) return {};
  if (!detailed) return chain[0];
  for (let i = 0; i < chain.length; i++) {
    const next = chain[i + 1];
    if (next) chain[i].issuerCertificate = next;
    else if (JSON.stringify(chain[i].subject) === JSON.stringify(chain[i].issuer)) chain[i].issuerCertificate = chain[i];
  }
  return chain[0];
}

/**
 * Node wraps a stream that isn't a socket with an internal class; http2-wrapper reaches for it at load
 * (`new tls.TLSSocket(stream)._handle._parentWrap.constructor`). It exists here so that load works.
 */
class JSStreamSocket {
  constructor(stream) { this.stream = stream; }
}

export class TLSSocket extends Socket {
  constructor(socket, options = {}) {
    super(options);
    this.encrypted = true;
    this.authorized = false;
    this.authorizationError = null;
    this.alpnProtocol = false;
    // As Node's client side: the name the script asked for, or false (the host name is still sent for SNI).
    this.servername = options.servername ?? false;
    this._tls = null;
    this._options = options;
    this._securing = false;
    if (socket && !(socket instanceof Socket)) this._handle = { _parentWrap: new JSStreamSocket(socket) };
  }

  /** The handshake is done: check the peer as Node does, then announce it. */
  _secured(info) {
    this._tls = info.tls ?? null;
    this.alpnProtocol = this._tls?.alpnProtocol ?? false;
    this.authorized = Boolean(this._tls?.authorized);
    this.authorizationError = this._tls?.authorizationError ?? null;
    const reject = this._options.rejectUnauthorized !== false && !(this._options.rejectUnauthorized === undefined
      && globalThis.process?.env?.NODE_TLS_REJECT_UNAUTHORIZED === '0');
    const custom = typeof this._options.checkServerIdentity === 'function' && this._options.checkServerIdentity !== checkServerIdentity;
    // The host judged the chain (and refused it unless told not to); the host name is checked here, as Node does it,
    // with the script's own check if it brought one. Nothing the script wrote has gone yet.
    if (this.authorized) {
      const identity = (custom ? this._options.checkServerIdentity : checkServerIdentity)(this.servername || this._host, this.getPeerCertificate());
      if (identity) {
        this.authorized = false;
        this.authorizationError = identity.code ?? 'ERR_TLS_CERT_ALTNAME_INVALID';
        if (reject) { this.destroy(identity); return false; }
      }
    }
    this.emit('secureConnect');
    return true;
  }

  /** Upgrades the connected net.Socket `socket` in place, and takes over its data from here on. */
  _upgrade(socket, tlsOptions) {
    this._securing = true;
    this.connecting = true;
    const start = () => {
      if (socket.destroyed || socket._id === null) {
        this.destroy(nodeError(Error, 'ECONNRESET', 'The socket closed before TLS could start', { syscall: 'read' }));
        return;
      }
      this._host = socket._host;
      this._port = socket._port;
      this._id = socket._id;
      this._conn = socket._conn;
      // The old socket stops reading and won't close the connection; this one owns it now. It closes with this one,
      // as Node's does, so code listening on it (pg listens for 'close' there) sees the connection end.
      socket._handedOver = true;
      this._underlying = socket;
      if (this._conn) this._conn.owner = this;
      internals.upgrade(this._id, tlsOptions).then(surfaced((info) => {
        if (this.destroyed) return;
        this.connecting = false;
        this.pending = false;
        this._securing = false;
        this._describe(info);
        if (this._secured(info)) this._flush();
      }), surfaced((err) => this.destroy(socketError(err, this._host, this._port))));
    };
    if (socket.connecting) socket.once('connect', start);
    else nextTick(start);
  }

  _destroy(err, callback) {
    super._destroy(err, (error) => {
      callback(error);
      if (this._underlying && !this._underlying.destroyed) this._underlying.destroy();
    });
  }

  getPeerCertificate(detailed) { return this._tls ? peerCertificate(this._tls, detailed) : null; }
  getPeerX509Certificate() { return undefined; }
  getProtocol() { return this._tls?.protocol ?? null; }
  getCipher() {
    if (!this._tls?.cipher) return undefined;
    return { name: this._tls.cipher, standardName: this._tls.cipher, version: this._tls.protocol };
  }
  getSession() { return undefined; }
  getTLSTicket() { return undefined; }
  isSessionReused() { return false; }
  getEphemeralKeyInfo() { return {}; }
  getFinished() { return undefined; }
  getPeerFinished() { return undefined; }
  getSharedSigalgs() { return []; }
  setServername(name) { this.servername = String(name); }
  setMaxSendFragment() { return true; }
  disableRenegotiation() {}
  enableTrace() {}
  renegotiate(options, callback) {
    const err = nodeError(Error, 'ERR_TLS_RENEGOTIATION_DISABLED', 'TLS session renegotiation disabled for this socket');
    if (typeof callback === 'function') nextTick(callback, err);
    return false;
  }
  exportKeyingMaterial() { throw unsupported('exportKeyingMaterial', 'the host keeps the TLS session'); }
}

/** tls.connect: (options[, cb]), (port[, host][, options][, cb]). With `socket`, upgrades that connected net.Socket. */
export function connect(...args) {
  let [options, callback] = normalizeArgs(args);
  if (typeof args[0] === 'number' || (typeof args[0] === 'string' && /^\d+$/.test(args[0]))) {
    const extra = args.find((a, i) => i > 0 && a !== null && typeof a === 'object');
    if (extra) options = { ...extra, ...options };
  }
  if (options.path !== undefined) throw unsupported('TLS over a Unix socket', 'the isolate reaches only TCP addresses');
  const host = options.host ?? options.socket?._host ?? 'localhost';
  const tlsOptions = hostOptions(options, host);
  const socket = new TLSSocket(null, options);
  if (callback) socket.once('secureConnect', callback);
  if (options.socket) {
    if (!(options.socket instanceof Socket)) {
      throw unsupported('TLS over a stream that isn\'t a net.Socket', 'the host runs TLS on its own connections');
    }
    socket._upgrade(options.socket, tlsOptions);
    return socket;
  }
  socket._tlsOptions = tlsOptions;
  return socket.connect({ ...options, host });
}

export class Server extends EventEmitter {
  constructor() { super(); throw unsupported('tls.Server', NO_SERVERS); }
}
export function createServer() { throw unsupported('tls.createServer', NO_SERVERS); }
export function getCiphers() { return ['tls_aes_128_gcm_sha256', 'tls_aes_256_gcm_sha384', 'tls_chacha20_poly1305_sha256']; }

export default { DEFAULT_ECDH_CURVE, DEFAULT_MIN_VERSION, DEFAULT_MAX_VERSION, DEFAULT_CIPHERS, rootCertificates,
  checkServerIdentity, TLSSocket, SecureContext, Server, connect, createServer, createSecureContext, getCiphers };
