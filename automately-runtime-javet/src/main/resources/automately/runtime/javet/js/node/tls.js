// A stand-in for Node's `tls` (D-109): its defaults and names, so libraries that reference it at load (https-proxy-agent)
// work. TLS sockets aren't available yet: connecting or serving throws ERR_NOT_SUPPORTED (phase B5). HTTPS requests go
// through node:https, which verifies certificates on the host.
import { EventEmitter } from 'node:events';
import { Socket } from 'node:net';

function unsupported(what) {
  const err = new Error(`${what} isn't available in the isolate yet: TLS sockets come with phase B5; use node:https or fetch`);
  err.code = 'ERR_NOT_SUPPORTED';
  return err;
}

export const DEFAULT_ECDH_CURVE = 'auto';
export const DEFAULT_MIN_VERSION = 'TLSv1.2';
export const DEFAULT_MAX_VERSION = 'TLSv1.3';
export const DEFAULT_CIPHERS = 'TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256';
/** The host verifies against the JVM's trust store, which the isolate can't list. */
export const rootCertificates = Object.freeze([]);

/**
 * Node's default identity check, as a marker: node:https accepts it (it's what verification does anyway) and refuses
 * any other function, which the isolate can't run against a certificate it never sees.
 */
export function checkServerIdentity() { return undefined; }

/**
 * Node wraps a stream that isn't a socket with an internal class; http2-wrapper reaches for it at load
 * (`new tls.TLSSocket(stream)._handle._parentWrap.constructor`). It exists here so that load works; HTTP/1 over an
 * HTTP/2 proxy, its only use, can't go through node:http's host connections anyway.
 */
class JSStreamSocket {
  constructor(stream) { this.stream = stream; }
}

/** An unconnected TLS socket, as Node makes one (D-114); connecting throws until phase B5. */
export class TLSSocket extends Socket {
  constructor(socket, options = {}) {
    super(options);
    this.encrypted = true;
    this.authorized = false;
    this.alpnProtocol = false;
    if (socket && !(socket instanceof Socket)) this._handle = { _parentWrap: new JSStreamSocket(socket) };
  }
  getPeerCertificate() { return null; }
  getProtocol() { return null; }
  getCipher() { return undefined; }
  getSession() { return undefined; }
  isSessionReused() { return false; }
}
export class SecureContext {}
export class Server extends EventEmitter {
  constructor() { super(); throw unsupported('tls.Server'); }
}
export function connect() { throw unsupported('tls.connect'); }
export function createServer() { throw unsupported('tls.createServer'); }
export function createSecureContext() { throw unsupported('tls.createSecureContext'); }
export function getCiphers() { return ['tls_aes_128_gcm_sha256', 'tls_aes_256_gcm_sha384', 'tls_chacha20_poly1305_sha256']; }

export default { DEFAULT_ECDH_CURVE, DEFAULT_MIN_VERSION, DEFAULT_MAX_VERSION, DEFAULT_CIPHERS, rootCertificates,
  checkServerIdentity, TLSSocket, SecureContext, Server, connect, createServer, createSecureContext, getCiphers };
