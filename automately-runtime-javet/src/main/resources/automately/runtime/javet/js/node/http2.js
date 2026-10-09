// Node's `http2` client in the isolate (D-109, D-117): sessions on the host's HTTP/2 connections, through the egress
// rules, counted as sockets, their streams as requests. Over TLS a session is negotiated with ALPN h2 and verified as
// tls sockets are; for http:// it speaks HTTP/2 from the first byte (h2c), as Node's client does. Servers never run on
// the cluster's nodes. The constants are Node 26's, generated from it.
import { EventEmitter } from "node:events";
import { Duplex } from "node:stream";
import { Buffer } from "node:buffer";
import { nextTick } from "node:process";
import { checkServerIdentity, peerCertificate } from "node:tls";
import { isIP, Socket } from "node:net";

export const constants = Object.freeze({
  NGHTTP2_ERR_FRAME_SIZE_ERROR: -522,
  NGHTTP2_SESSION_SERVER: 0,
  NGHTTP2_SESSION_CLIENT: 1,
  NGHTTP2_STREAM_STATE_IDLE: 1,
  NGHTTP2_STREAM_STATE_OPEN: 2,
  NGHTTP2_STREAM_STATE_RESERVED_LOCAL: 3,
  NGHTTP2_STREAM_STATE_RESERVED_REMOTE: 4,
  NGHTTP2_STREAM_STATE_HALF_CLOSED_LOCAL: 5,
  NGHTTP2_STREAM_STATE_HALF_CLOSED_REMOTE: 6,
  NGHTTP2_STREAM_STATE_CLOSED: 7,
  NGHTTP2_FLAG_NONE: 0,
  NGHTTP2_FLAG_END_STREAM: 1,
  NGHTTP2_FLAG_END_HEADERS: 4,
  NGHTTP2_FLAG_ACK: 1,
  NGHTTP2_FLAG_PADDED: 8,
  NGHTTP2_FLAG_PRIORITY: 32,
  DEFAULT_SETTINGS_HEADER_TABLE_SIZE: 4096,
  DEFAULT_SETTINGS_ENABLE_PUSH: 1,
  DEFAULT_SETTINGS_MAX_CONCURRENT_STREAMS: 4294967295,
  DEFAULT_SETTINGS_INITIAL_WINDOW_SIZE: 65535,
  DEFAULT_SETTINGS_MAX_FRAME_SIZE: 16384,
  DEFAULT_SETTINGS_MAX_HEADER_LIST_SIZE: 65535,
  DEFAULT_SETTINGS_ENABLE_CONNECT_PROTOCOL: 0,
  MAX_MAX_FRAME_SIZE: 16777215,
  MIN_MAX_FRAME_SIZE: 16384,
  MAX_INITIAL_WINDOW_SIZE: 2147483647,
  NGHTTP2_SETTINGS_HEADER_TABLE_SIZE: 1,
  NGHTTP2_SETTINGS_ENABLE_PUSH: 2,
  NGHTTP2_SETTINGS_MAX_CONCURRENT_STREAMS: 3,
  NGHTTP2_SETTINGS_INITIAL_WINDOW_SIZE: 4,
  NGHTTP2_SETTINGS_MAX_FRAME_SIZE: 5,
  NGHTTP2_SETTINGS_MAX_HEADER_LIST_SIZE: 6,
  NGHTTP2_SETTINGS_ENABLE_CONNECT_PROTOCOL: 8,
  PADDING_STRATEGY_NONE: 0,
  PADDING_STRATEGY_ALIGNED: 1,
  PADDING_STRATEGY_MAX: 2,
  PADDING_STRATEGY_CALLBACK: 1,
  NGHTTP2_NO_ERROR: 0,
  NGHTTP2_PROTOCOL_ERROR: 1,
  NGHTTP2_INTERNAL_ERROR: 2,
  NGHTTP2_FLOW_CONTROL_ERROR: 3,
  NGHTTP2_SETTINGS_TIMEOUT: 4,
  NGHTTP2_STREAM_CLOSED: 5,
  NGHTTP2_FRAME_SIZE_ERROR: 6,
  NGHTTP2_REFUSED_STREAM: 7,
  NGHTTP2_CANCEL: 8,
  NGHTTP2_COMPRESSION_ERROR: 9,
  NGHTTP2_CONNECT_ERROR: 10,
  NGHTTP2_ENHANCE_YOUR_CALM: 11,
  NGHTTP2_INADEQUATE_SECURITY: 12,
  NGHTTP2_HTTP_1_1_REQUIRED: 13,
  NGHTTP2_DEFAULT_WEIGHT: 16,
  HTTP2_HEADER_STATUS: ":status",
  HTTP2_HEADER_METHOD: ":method",
  HTTP2_HEADER_AUTHORITY: ":authority",
  HTTP2_HEADER_SCHEME: ":scheme",
  HTTP2_HEADER_PATH: ":path",
  HTTP2_HEADER_PROTOCOL: ":protocol",
  HTTP2_HEADER_ACCEPT_ENCODING: "accept-encoding",
  HTTP2_HEADER_ACCEPT_LANGUAGE: "accept-language",
  HTTP2_HEADER_ACCEPT_RANGES: "accept-ranges",
  HTTP2_HEADER_ACCEPT: "accept",
  HTTP2_HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS: "access-control-allow-credentials",
  HTTP2_HEADER_ACCESS_CONTROL_ALLOW_HEADERS: "access-control-allow-headers",
  HTTP2_HEADER_ACCESS_CONTROL_ALLOW_METHODS: "access-control-allow-methods",
  HTTP2_HEADER_ACCESS_CONTROL_ALLOW_ORIGIN: "access-control-allow-origin",
  HTTP2_HEADER_ACCESS_CONTROL_EXPOSE_HEADERS: "access-control-expose-headers",
  HTTP2_HEADER_ACCESS_CONTROL_REQUEST_HEADERS: "access-control-request-headers",
  HTTP2_HEADER_ACCESS_CONTROL_REQUEST_METHOD: "access-control-request-method",
  HTTP2_HEADER_AGE: "age",
  HTTP2_HEADER_AUTHORIZATION: "authorization",
  HTTP2_HEADER_CACHE_CONTROL: "cache-control",
  HTTP2_HEADER_CONNECTION: "connection",
  HTTP2_HEADER_CONTENT_DISPOSITION: "content-disposition",
  HTTP2_HEADER_CONTENT_ENCODING: "content-encoding",
  HTTP2_HEADER_CONTENT_LENGTH: "content-length",
  HTTP2_HEADER_CONTENT_TYPE: "content-type",
  HTTP2_HEADER_COOKIE: "cookie",
  HTTP2_HEADER_DATE: "date",
  HTTP2_HEADER_ETAG: "etag",
  HTTP2_HEADER_FORWARDED: "forwarded",
  HTTP2_HEADER_HOST: "host",
  HTTP2_HEADER_IF_MODIFIED_SINCE: "if-modified-since",
  HTTP2_HEADER_IF_NONE_MATCH: "if-none-match",
  HTTP2_HEADER_IF_RANGE: "if-range",
  HTTP2_HEADER_LAST_MODIFIED: "last-modified",
  HTTP2_HEADER_LINK: "link",
  HTTP2_HEADER_LOCATION: "location",
  HTTP2_HEADER_RANGE: "range",
  HTTP2_HEADER_REFERER: "referer",
  HTTP2_HEADER_SERVER: "server",
  HTTP2_HEADER_SET_COOKIE: "set-cookie",
  HTTP2_HEADER_STRICT_TRANSPORT_SECURITY: "strict-transport-security",
  HTTP2_HEADER_TRANSFER_ENCODING: "transfer-encoding",
  HTTP2_HEADER_TE: "te",
  HTTP2_HEADER_UPGRADE_INSECURE_REQUESTS: "upgrade-insecure-requests",
  HTTP2_HEADER_UPGRADE: "upgrade",
  HTTP2_HEADER_USER_AGENT: "user-agent",
  HTTP2_HEADER_VARY: "vary",
  HTTP2_HEADER_X_CONTENT_TYPE_OPTIONS: "x-content-type-options",
  HTTP2_HEADER_X_FRAME_OPTIONS: "x-frame-options",
  HTTP2_HEADER_KEEP_ALIVE: "keep-alive",
  HTTP2_HEADER_PROXY_CONNECTION: "proxy-connection",
  HTTP2_HEADER_X_XSS_PROTECTION: "x-xss-protection",
  HTTP2_HEADER_ALT_SVC: "alt-svc",
  HTTP2_HEADER_CONTENT_SECURITY_POLICY: "content-security-policy",
  HTTP2_HEADER_EARLY_DATA: "early-data",
  HTTP2_HEADER_EXPECT_CT: "expect-ct",
  HTTP2_HEADER_ORIGIN: "origin",
  HTTP2_HEADER_PURPOSE: "purpose",
  HTTP2_HEADER_TIMING_ALLOW_ORIGIN: "timing-allow-origin",
  HTTP2_HEADER_X_FORWARDED_FOR: "x-forwarded-for",
  HTTP2_HEADER_PRIORITY: "priority",
  HTTP2_HEADER_ACCEPT_CHARSET: "accept-charset",
  HTTP2_HEADER_ACCESS_CONTROL_MAX_AGE: "access-control-max-age",
  HTTP2_HEADER_ALLOW: "allow",
  HTTP2_HEADER_CONTENT_LANGUAGE: "content-language",
  HTTP2_HEADER_CONTENT_LOCATION: "content-location",
  HTTP2_HEADER_CONTENT_MD5: "content-md5",
  HTTP2_HEADER_CONTENT_RANGE: "content-range",
  HTTP2_HEADER_DNT: "dnt",
  HTTP2_HEADER_EXPECT: "expect",
  HTTP2_HEADER_EXPIRES: "expires",
  HTTP2_HEADER_FROM: "from",
  HTTP2_HEADER_IF_MATCH: "if-match",
  HTTP2_HEADER_IF_UNMODIFIED_SINCE: "if-unmodified-since",
  HTTP2_HEADER_MAX_FORWARDS: "max-forwards",
  HTTP2_HEADER_PREFER: "prefer",
  HTTP2_HEADER_PROXY_AUTHENTICATE: "proxy-authenticate",
  HTTP2_HEADER_PROXY_AUTHORIZATION: "proxy-authorization",
  HTTP2_HEADER_REFRESH: "refresh",
  HTTP2_HEADER_RETRY_AFTER: "retry-after",
  HTTP2_HEADER_TRAILER: "trailer",
  HTTP2_HEADER_TK: "tk",
  HTTP2_HEADER_VIA: "via",
  HTTP2_HEADER_WARNING: "warning",
  HTTP2_HEADER_WWW_AUTHENTICATE: "www-authenticate",
  HTTP2_HEADER_HTTP2_SETTINGS: "http2-settings",
  HTTP2_METHOD_ACL: "ACL",
  HTTP2_METHOD_BASELINE_CONTROL: "BASELINE-CONTROL",
  HTTP2_METHOD_BIND: "BIND",
  HTTP2_METHOD_CHECKIN: "CHECKIN",
  HTTP2_METHOD_CHECKOUT: "CHECKOUT",
  HTTP2_METHOD_CONNECT: "CONNECT",
  HTTP2_METHOD_COPY: "COPY",
  HTTP2_METHOD_DELETE: "DELETE",
  HTTP2_METHOD_GET: "GET",
  HTTP2_METHOD_HEAD: "HEAD",
  HTTP2_METHOD_LABEL: "LABEL",
  HTTP2_METHOD_LINK: "LINK",
  HTTP2_METHOD_LOCK: "LOCK",
  HTTP2_METHOD_MERGE: "MERGE",
  HTTP2_METHOD_MKACTIVITY: "MKACTIVITY",
  HTTP2_METHOD_MKCALENDAR: "MKCALENDAR",
  HTTP2_METHOD_MKCOL: "MKCOL",
  HTTP2_METHOD_MKREDIRECTREF: "MKREDIRECTREF",
  HTTP2_METHOD_MKWORKSPACE: "MKWORKSPACE",
  HTTP2_METHOD_MOVE: "MOVE",
  HTTP2_METHOD_OPTIONS: "OPTIONS",
  HTTP2_METHOD_ORDERPATCH: "ORDERPATCH",
  HTTP2_METHOD_PATCH: "PATCH",
  HTTP2_METHOD_POST: "POST",
  HTTP2_METHOD_PRI: "PRI",
  HTTP2_METHOD_PROPFIND: "PROPFIND",
  HTTP2_METHOD_PROPPATCH: "PROPPATCH",
  HTTP2_METHOD_PUT: "PUT",
  HTTP2_METHOD_REBIND: "REBIND",
  HTTP2_METHOD_REPORT: "REPORT",
  HTTP2_METHOD_SEARCH: "SEARCH",
  HTTP2_METHOD_TRACE: "TRACE",
  HTTP2_METHOD_UNBIND: "UNBIND",
  HTTP2_METHOD_UNCHECKOUT: "UNCHECKOUT",
  HTTP2_METHOD_UNLINK: "UNLINK",
  HTTP2_METHOD_UNLOCK: "UNLOCK",
  HTTP2_METHOD_UPDATE: "UPDATE",
  HTTP2_METHOD_UPDATEREDIRECTREF: "UPDATEREDIRECTREF",
  HTTP2_METHOD_VERSION_CONTROL: "VERSION-CONTROL",
  HTTP_STATUS_CONTINUE: 100,
  HTTP_STATUS_SWITCHING_PROTOCOLS: 101,
  HTTP_STATUS_PROCESSING: 102,
  HTTP_STATUS_EARLY_HINTS: 103,
  HTTP_STATUS_OK: 200,
  HTTP_STATUS_CREATED: 201,
  HTTP_STATUS_ACCEPTED: 202,
  HTTP_STATUS_NON_AUTHORITATIVE_INFORMATION: 203,
  HTTP_STATUS_NO_CONTENT: 204,
  HTTP_STATUS_RESET_CONTENT: 205,
  HTTP_STATUS_PARTIAL_CONTENT: 206,
  HTTP_STATUS_MULTI_STATUS: 207,
  HTTP_STATUS_ALREADY_REPORTED: 208,
  HTTP_STATUS_IM_USED: 226,
  HTTP_STATUS_MULTIPLE_CHOICES: 300,
  HTTP_STATUS_MOVED_PERMANENTLY: 301,
  HTTP_STATUS_FOUND: 302,
  HTTP_STATUS_SEE_OTHER: 303,
  HTTP_STATUS_NOT_MODIFIED: 304,
  HTTP_STATUS_USE_PROXY: 305,
  HTTP_STATUS_TEMPORARY_REDIRECT: 307,
  HTTP_STATUS_PERMANENT_REDIRECT: 308,
  HTTP_STATUS_BAD_REQUEST: 400,
  HTTP_STATUS_UNAUTHORIZED: 401,
  HTTP_STATUS_PAYMENT_REQUIRED: 402,
  HTTP_STATUS_FORBIDDEN: 403,
  HTTP_STATUS_NOT_FOUND: 404,
  HTTP_STATUS_METHOD_NOT_ALLOWED: 405,
  HTTP_STATUS_NOT_ACCEPTABLE: 406,
  HTTP_STATUS_PROXY_AUTHENTICATION_REQUIRED: 407,
  HTTP_STATUS_REQUEST_TIMEOUT: 408,
  HTTP_STATUS_CONFLICT: 409,
  HTTP_STATUS_GONE: 410,
  HTTP_STATUS_LENGTH_REQUIRED: 411,
  HTTP_STATUS_PRECONDITION_FAILED: 412,
  HTTP_STATUS_PAYLOAD_TOO_LARGE: 413,
  HTTP_STATUS_URI_TOO_LONG: 414,
  HTTP_STATUS_UNSUPPORTED_MEDIA_TYPE: 415,
  HTTP_STATUS_RANGE_NOT_SATISFIABLE: 416,
  HTTP_STATUS_EXPECTATION_FAILED: 417,
  HTTP_STATUS_TEAPOT: 418,
  HTTP_STATUS_MISDIRECTED_REQUEST: 421,
  HTTP_STATUS_UNPROCESSABLE_ENTITY: 422,
  HTTP_STATUS_LOCKED: 423,
  HTTP_STATUS_FAILED_DEPENDENCY: 424,
  HTTP_STATUS_TOO_EARLY: 425,
  HTTP_STATUS_UPGRADE_REQUIRED: 426,
  HTTP_STATUS_PRECONDITION_REQUIRED: 428,
  HTTP_STATUS_TOO_MANY_REQUESTS: 429,
  HTTP_STATUS_REQUEST_HEADER_FIELDS_TOO_LARGE: 431,
  HTTP_STATUS_UNAVAILABLE_FOR_LEGAL_REASONS: 451,
  HTTP_STATUS_INTERNAL_SERVER_ERROR: 500,
  HTTP_STATUS_NOT_IMPLEMENTED: 501,
  HTTP_STATUS_BAD_GATEWAY: 502,
  HTTP_STATUS_SERVICE_UNAVAILABLE: 503,
  HTTP_STATUS_GATEWAY_TIMEOUT: 504,
  HTTP_STATUS_HTTP_VERSION_NOT_SUPPORTED: 505,
  HTTP_STATUS_VARIANT_ALSO_NEGOTIATES: 506,
  HTTP_STATUS_INSUFFICIENT_STORAGE: 507,
  HTTP_STATUS_LOOP_DETECTED: 508,
  HTTP_STATUS_BANDWIDTH_LIMIT_EXCEEDED: 509,
  HTTP_STATUS_NOT_EXTENDED: 510,
  HTTP_STATUS_NETWORK_AUTHENTICATION_REQUIRED: 511,
});

const internals = globalThis[Symbol.for("automately.http2")];
const reportUncaught = globalThis[Symbol.for("automately.uncaught")];
const surfaced = (fn) => (...args) => { try { return fn(...args); } catch (e) { reportUncaught(e, "an http2 event listener"); return undefined; } };

function nodeError(Type, code, message, extra) {
  const err = new Type(message);
  err.code = code;
  return extra ? Object.assign(err, extra) : err;
}

function unsupported(what, why) {
  return nodeError(Error, "ERR_NOT_SUPPORTED", `${what} isn't available in the isolate: ${why}`);
}
const NO_SERVERS = "HTTP/2 servers don't run on the cluster's nodes, where inbound traffic is HTTP by hostname through the ingress";

/** Node's name for an error code, as its errors spell it. */
const CODE_NAMES = Object.fromEntries(Object.entries(constants)
  .filter(([name, value]) => /^NGHTTP2_(NO_ERROR|PROTOCOL_ERROR|INTERNAL_ERROR|FLOW_CONTROL_ERROR|SETTINGS_TIMEOUT|STREAM_CLOSED|FRAME_SIZE_ERROR|REFUSED_STREAM|CANCEL|COMPRESSION_ERROR|CONNECT_ERROR|ENHANCE_YOUR_CALM|INADEQUATE_SECURITY|HTTP_1_1_REQUIRED)$/.test(name))
  .map(([name, value]) => [value, name]));

export const sensitiveHeaders = Symbol("nodejs.http2.sensitiveHeaders");

// --- settings ---

const DEFAULT_SETTINGS = { headerTableSize: 4096, enablePush: true, initialWindowSize: 65535, maxFrameSize: 16384,
  maxConcurrentStreams: 4294967295, maxHeaderSize: 65535, maxHeaderListSize: 65535, enableConnectProtocol: false };
export function getDefaultSettings() { return { ...DEFAULT_SETTINGS }; }

const SETTING_IDS = { headerTableSize: 1, enablePush: 2, maxConcurrentStreams: 3, initialWindowSize: 4, maxFrameSize: 5,
  maxHeaderListSize: 6, enableConnectProtocol: 8 };

/** SETTINGS as the frame's payload: six bytes each, a 16-bit identifier and a 32-bit value. */
export function getPackedSettings(settings = {}) {
  const entries = [];
  for (const [name, id] of Object.entries(SETTING_IDS)) {
    const value = name === "maxHeaderListSize" ? settings.maxHeaderListSize ?? settings.maxHeaderSize : settings[name];
    if (value === undefined) continue;
    entries.push([id, typeof value === "boolean" ? Number(value) : value]);
  }
  const out = Buffer.alloc(entries.length * 6);
  entries.forEach(([id, value], i) => { out.writeUInt16BE(id, i * 6); out.writeUInt32BE(value >>> 0, i * 6 + 2); });
  return out;
}

export function getUnpackedSettings(buffer) {
  const bytes = Buffer.from(buffer);
  if (bytes.length % 6 !== 0) throw nodeError(RangeError, "ERR_HTTP2_INVALID_PACKED_SETTINGS_LENGTH", "Packed settings length must be a multiple of six");
  const out = {};
  for (let i = 0; i < bytes.length; i += 6) {
    const id = bytes.readUInt16BE(i);
    const value = bytes.readUInt32BE(i + 2);
    switch (id) {
      case 1: out.headerTableSize = value; break;
      case 2: out.enablePush = value !== 0; break;
      case 3: out.maxConcurrentStreams = value; break;
      case 4: out.initialWindowSize = value; break;
      case 5: out.maxFrameSize = value; break;
      case 6: out.maxHeaderListSize = out.maxHeaderSize = value; break;
      case 8: out.enableConnectProtocol = value !== 0; break;
      default: (out.customSettings ??= {})[id] = value;
    }
  }
  return out;
}

// --- headers, with Node's rules (lib/internal/http2/util.js) ---

const SINGLE_VALUE = new Set([":status", ":method", ":authority", ":scheme", ":path", ":protocol", "access-control-allow-credentials",
  "access-control-max-age", "access-control-request-method", "age", "authorization", "content-encoding", "content-language",
  "content-length", "content-location", "content-md5", "content-range", "content-type", "date", "dnt", "etag", "expires", "from",
  "host", "if-match", "if-modified-since", "if-none-match", "if-range", "if-unmodified-since", "last-modified", "location",
  "max-forwards", "proxy-authorization", "range", "referer", "retry-after", "tk", "upgrade-insecure-requests", "user-agent",
  "x-content-type-options"]);

function toHeaderObject(raw) {
  const out = { __proto__: null };
  for (let i = 0; i + 1 < raw.length; i += 2) {
    const name = raw[i];
    let value = raw[i + 1];
    if (name === ":status") value = Number(value);
    const existing = out[name];
    if (existing === undefined) out[name] = name === "set-cookie" ? [value] : value;
    else if (!SINGLE_VALUE.has(name)) {
      if (name === "cookie") out[name] = `${existing}; ${value}`;
      else if (name === "set-cookie") existing.push(value);
      else out[name] = `${existing}, ${value}`;
    }
  }
  out[sensitiveHeaders] = [];
  return out;
}

const CONNECTION_HEADERS = new Set(["connection", "upgrade", "http2-settings", "te", "transfer-encoding", "keep-alive", "proxy-connection"]);

/** The request's headers for the host, as Node checks them. */
function requestHeaders(headers, session) {
  const out = {};
  for (const [name, value] of Object.entries(headers ?? {})) {
    if (value === undefined) continue;
    const lower = String(name).toLowerCase();
    if (CONNECTION_HEADERS.has(lower) && !(lower === "te" && String(value) === "trailers")) {
      throw nodeError(TypeError, "ERR_HTTP2_INVALID_CONNECTION_HEADERS", `HTTP/1 Connection specific headers are forbidden: "${lower}"`);
    }
    if (lower.startsWith(":") && ![":method", ":path", ":scheme", ":authority", ":protocol"].includes(lower)) {
      throw nodeError(TypeError, "ERR_HTTP2_INVALID_PSEUDOHEADER", `"${lower}" is an invalid pseudoheader or is used incorrectly`);
    }
    out[lower] = Array.isArray(value) ? value.map(String) : String(value);
  }
  out[":method"] ??= "GET";
  out[":scheme"] ??= session._scheme;
  out[":authority"] ??= session._authority;
  if (out[":method"] === "CONNECT") throw unsupported("CONNECT over HTTP/2", "tunnels would bypass the egress rules");
  out[":path"] ??= "/";
  return out;
}

// --- streams ---

const NO_PAYLOAD = new Set(["DELETE", "GET", "HEAD"]);

export class ClientHttp2Stream extends Duplex {
  constructor(session, headers, options) {
    super({ allowHalfOpen: true, emitClose: false, highWaterMark: options.highWaterMark });
    this.session = session;
    this.sentHeaders = headers;
    this.sentTrailers = undefined;
    this.rstCode = undefined;
    this.aborted = false;
    this._closed = false;
    this.pending = true;
    this.endAfterHeaders = false;
    this.state = {};
    this.timeout = 0;
    this._id = null;
    this._streamId = undefined;
    this._iterator = null;
    this._pulling = false;
    this._waiting = [];
    this._timer = null;
    this._endStream = options.endStream;
    // A request with no body: the writable side ends at once, as Node ends it.
    if (this._endStream) this.end();
    if (options.signal) {
      const onAbort = () => this.destroy(nodeError(Error, "ABORT_ERR", "The operation was aborted", { name: "AbortError" }));
      if (options.signal.aborted) nextTick(onAbort);
      else options.signal.addEventListener("abort", onAbort, { once: true });
    }
  }

  get id() { return this._streamId; }
  // Node's Http2Stream has its own: closed once RST_STREAM went or came, or it ended both ways.
  get closed() { return this._closed; }
  get bufferSize() { return this.writableLength; }

  /** The host opened the stream: its headers have gone. */
  _opened(id, streamId) {
    this._id = id;
    this._streamId = streamId;
    this.pending = false;
    this.emit("ready");
    for (const go of this._waiting.splice(0)) go();
    internals.response(id).then(surfaced(({ rawHeaders, status, body }) => {
      if (this.destroyed) { body.cancel(); return; }
      const headers = toHeaderObject(rawHeaders);
      this._iterator = body[Symbol.asyncIterator]();
      this._body = body;
      const informational = status >= 100 && status < 200;
      // Node's flags: PRIORITY and END_HEADERS, as nghttp2 reports them, and END_STREAM when the answer has no body.
      const flags = constants.NGHTTP2_FLAG_PRIORITY | constants.NGHTTP2_FLAG_END_HEADERS
        | (this.sentHeaders[":method"] === "HEAD" || status === 204 || status === 304 ? constants.NGHTTP2_FLAG_END_STREAM : 0);
      this.endAfterHeaders = Boolean(flags & constants.NGHTTP2_FLAG_END_STREAM);
      this._touch();
      this.emit(informational ? "headers" : "response", headers, flags, rawHeaders);
      if (this._wantsRead) { this._wantsRead = false; this._pull(); }
    }), surfaced((err) => this._fail(err)));
  }

  _whenOpen(go) {
    if (this._id !== null) go();
    else this._waiting.push(go);
  }

  _pull() {
    if (this._pulling || !this._iterator) { this._wantsRead = !this._iterator; return; }
    this._pulling = true;
    this._iterator.next().then(surfaced(({ value, done }) => {
      this._pulling = false;
      if (done) { this._finish(); return; }
      this._touch();
      this.push(Buffer.from(value.buffer, value.byteOffset, value.byteLength));
    }), surfaced((err) => { this._pulling = false; this._fail(err); }));
  }

  /** The body has ended: trailers first, as Node emits them, then the end. */
  _finish() {
    internals.trailers(this._id).then(surfaced(({ rawTrailers }) => {
      if (rawTrailers.length) this.emit("trailers", toHeaderObject(rawTrailers), constants.NGHTTP2_FLAG_END_STREAM | constants.NGHTTP2_FLAG_END_HEADERS);
      this.push(null);
    }), surfaced(() => this.push(null)));
  }

  _fail(err) {
    if (this.destroyed) return;
    const match = /error code (NGHTTP2_\w+)/.exec(err?.message ?? "");
    if (err?.code === "ERR_HTTP2_STREAM_ERROR" && match) {
      this.rstCode = constants[match[1]];
      // The peer reset the stream. NO_ERROR and CANCEL end it quietly, as in Node; any other code fails it.
      if (this.rstCode === constants.NGHTTP2_NO_ERROR || this.rstCode === constants.NGHTTP2_CANCEL) {
        this.push(null);
        return;
      }
      this.destroy(nodeError(Error, "ERR_HTTP2_STREAM_ERROR", `Stream closed with error code ${match[1]}`));
      return;
    }
    this.destroy(err instanceof Error ? err : nodeError(Error, err?.code ?? "ERR_HTTP2_STREAM_ERROR", err?.message ?? String(err)));
  }

  _read() {
    if (this._iterator) this._pull();
    else this._wantsRead = true;
  }

  _write(chunk, encoding, callback) {
    this._whenOpen(() => internals.write(this._id, chunk).then(surfaced(() => { this._touch(); callback(); }), surfaced((err) => callback(err))));
  }

  _final(callback) {
    if (this._endStream) { callback(); return; }
    this._whenOpen(() => internals.end(this._id).then(surfaced(() => callback()), surfaced((err) => callback(err))));
  }

  _destroy(err, callback) {
    this._clearTimer();
    this._closed = true;
    if (this.rstCode === undefined) this.rstCode = err ? constants.NGHTTP2_INTERNAL_ERROR : constants.NGHTTP2_NO_ERROR;
    if (this._id !== null && !this.readableEnded) internals.reset(this._id, this.rstCode === constants.NGHTTP2_NO_ERROR && !this.readableEnded
      ? constants.NGHTTP2_CANCEL : this.rstCode);
    this._body?.cancel();
    this.session._streams.delete(this);
    callback(err);
    nextTick(() => { this.emit("close"); this.session._maybeClose(); });
  }

  /** Resets the stream with `code` (RST_STREAM), as Node's close(). */
  close(code = constants.NGHTTP2_NO_ERROR, callback) {
    if (typeof code !== "number" || code < 0 || code > 4294967295) {
      throw nodeError(RangeError, "ERR_OUT_OF_RANGE", `The value of "code" is out of range. It must be >= 0 && <= 4294967295. Received ${code}`);
    }
    if (callback) this.once("close", callback);
    if (this._closed) return;
    this.rstCode = code;
    if (this._id !== null) internals.reset(this._id, code);
    this._body?.cancel();
    this.destroy();
  }

  priority() {}
  sendTrailers() { throw unsupported("sendTrailers", "request trailers aren't supported yet"); }

  setTimeout(ms, callback) {
    this.timeout = Number(ms) || 0;
    if (callback) this.once("timeout", callback);
    this._touch();
    return this;
  }

  _touch() {
    this._clearTimer();
    if (this.timeout > 0 && !this.destroyed) this._timer = setTimeout(() => { this._timer = null; this.emit("timeout"); }, this.timeout);
  }

  _clearTimer() { if (this._timer) { clearTimeout(this._timer); this._timer = null; } }
}

// --- sessions ---

/** What Node's session.socket offers, with the connection's real details. */
class SocketProxy extends EventEmitter {
  constructor(session) {
    super();
    this._session = session;
  }
  get remoteAddress() { return this._session._info?.remoteAddress; }
  get remotePort() { return this._session._info?.remotePort; }
  get remoteFamily() { return this._session._info?.remoteFamily; }
  get localAddress() { return this._session._info?.localAddress; }
  get localPort() { return this._session._info?.localPort; }
  get encrypted() { return this._session.encrypted; }
  get alpnProtocol() { return this._session.alpnProtocol; }
  get servername() { return this._session._servername; }
  get authorized() { return Boolean(this._session._info?.tls?.authorized); }
  get destroyed() { return this._session.destroyed; }
  getPeerCertificate(detailed) { return this._session._info?.tls ? peerCertificate(this._session._info.tls, detailed) : null; }
  getProtocol() { return this._session._info?.tls?.protocol ?? null; }
  setTimeout(ms, callback) { this._session.setTimeout(ms, callback); return this; }
  setNoDelay() { return this; }
  setKeepAlive() { return this; }
  ref() { return this; }
  unref() { return this; }
  destroy(err) { this._session.destroy(err); return this; }
  end() { this._session.close(); return this; }
}

export class ClientHttp2Session extends EventEmitter {
  constructor(authority, options) {
    super();
    this.type = constants.NGHTTP2_SESSION_CLIENT;
    this.connecting = true;
    this.closed = false;
    this.destroyed = false;
    this.encrypted = authority.protocol === "https:";
    this.alpnProtocol = undefined;
    this.originSet = undefined;
    this.pendingSettingsAck = false;
    this.localSettings = getDefaultSettings();
    this.localSettings.enablePush = false;
    this.remoteSettings = undefined;
    this.state = {};
    this.socket = new SocketProxy(this);
    this.timeout = 0;
    this._id = null;
    this._info = null;
    this._streams = new Set();
    this._waiting = [];
    this._closing = false;
    this._timer = null;
    this._scheme = authority.protocol.slice(0, -1);
    const port = authority.port || (this.encrypted ? "443" : "80");
    this._host = authority.hostname.replace(/^\[|\]$/g, "");
    this._authority = authority.host.includes(":") && !authority.port ? authority.host : `${authority.hostname}${authority.port ? ":" + authority.port : ""}`;
    this._options = options;
    this._servername = options.servername ?? (isIP(this._host) ? undefined : this._host);
    if (this.encrypted) this.originSet = [`${this._scheme}://${this._authority}`];
  }

  _connect(tlsOptions) {
    const request = { authority: `${this._scheme}://${this._authority.includes(":") ? this._authority : `${this._authority}:${this.encrypted ? 443 : 80}`}` };
    if (this.encrypted) request.tls = tlsOptions;
    internals.connect(request).then(surfaced(({ id, info, settings, events }) => {
      if (this.destroyed) { internals.destroy(id); events.cancel(); return; }
      this._id = id;
      this._info = info;
      this._hostEvents = events;
      this.connecting = false;
      this.remoteSettings = settings;
      if (this.encrypted) {
        this.alpnProtocol = info.tls?.alpnProtocol ?? "h2";
        // The host judged the chain; the name is checked here, with the script's own check if it brought one.
        const custom = typeof this._options.checkServerIdentity === "function" && this._options.checkServerIdentity !== checkServerIdentity;
        const reject = this._options.rejectUnauthorized !== false && globalThis.process?.env?.NODE_TLS_REJECT_UNAUTHORIZED !== "0";
        if (info.tls?.authorized && reject) {
          const identity = (custom ? this._options.checkServerIdentity : checkServerIdentity)(this._servername ?? this._host,
            peerCertificate(info.tls, false));
          if (identity) { this.destroy(identity); return; }
        }
      } else {
        this.alpnProtocol = "h2c";
      }
      this._listen();
      this._touch();
      // Node passes the TLS socket itself; session.socket is a proxy over it, so the two differ.
      this.emit("connect", this, new SocketProxy(this));
      this.emit("remoteSettings", { ...settings });
      for (const go of this._waiting.splice(0)) go();
    }), surfaced((err) => this.destroy(sessionError(err, this._host))));
  }

  /** The connection's events, as the host reports them, until it closes. */
  async _listen() {
    try {
      for await (const chunk of this._hostEvents) {
        const event = JSON.parse(Buffer.from(chunk).toString("utf8"));
        if (event.type === "remoteSettings") {
          this.remoteSettings = event.settings;
          surfaced(() => this.emit("remoteSettings", { ...event.settings }))();
        } else if (event.type === "goaway") {
          surfaced(() => this.emit("goaway", event.errorCode, event.lastStreamID, Buffer.from(event.opaqueData, "base64")))();
          if (event.errorCode !== constants.NGHTTP2_NO_ERROR) {
            this.destroy(nodeError(Error, "ERR_HTTP2_SESSION_ERROR", `Session closed with error code ${event.errorCode}`), event.errorCode);
          } else {
            this.close();
          }
        } else if (event.type === "error") {
          this.destroy(nodeError(Error, "ERR_HTTP2_ERROR", event.message));
        }
      }
    } catch {
      // The connection went away; the close below covers it.
    }
    this._closedByPeer();
  }

  _closedByPeer() {
    if (this.destroyed) return;
    this._id = null;
    this.destroy();
  }

  _whenConnected(go) {
    if (this._id !== null) go();
    else if (!this.destroyed) this._waiting.push(go);
  }

  request(headers = {}, options = {}) {
    if (this.closed && !this.destroyed) throw nodeError(Error, "ERR_HTTP2_GOAWAY_SESSION", "New streams cannot be created after receiving a GOAWAY");
    const sent = requestHeaders(headers, this);
    if (this.destroyed) {
      // As Node: the stream is made, and fails at once.
      const dead = new ClientHttp2Stream(this, sent, { ...options, endStream: true });
      nextTick(() => dead.destroy(nodeError(Error, "ERR_HTTP2_INVALID_SESSION", "The session has been destroyed")));
      return dead;
    }
    const endStream = options.endStream ?? NO_PAYLOAD.has(sent[":method"]);
    const stream = new ClientHttp2Stream(this, sent, { ...options, endStream });
    this._streams.add(stream);
    this._whenConnected(() => {
      if (stream.destroyed) return;
      internals.request(this._id, sent, Boolean(endStream && !options.waitForTrailers)).then(surfaced(({ id, streamId }) => {
        if (stream.destroyed) { internals.reset(id, constants.NGHTTP2_CANCEL); return; }
        stream._opened(id, streamId);
      }), surfaced((err) => stream._fail(err)));
    });
    return stream;
  }

  ping(payload, callback) {
    if (typeof payload === "function") { callback = payload; payload = undefined; }
    if (payload !== undefined && Buffer.from(payload).length !== 8) {
      throw nodeError(RangeError, "ERR_HTTP2_PING_LENGTH", "HTTP2 ping payload must be 8 bytes");
    }
    // Node's default payload: the time it was sent, as 8 bytes.
    const bytes = payload === undefined ? (() => { const b = Buffer.alloc(8); b.writeDoubleLE(Date.now()); return b; })() : Buffer.from(payload);
    this._whenConnected(() => internals.ping(this._id, bytes).then(surfaced(({ duration, payload: ack }) => {
      if (callback) callback(null, duration, Buffer.from(ack, "base64"));
    }), surfaced((err) => callback?.(sessionError(err, this._host)))));
    return true;
  }

  settings(settings, callback) {
    for (const [name, value] of Object.entries(settings ?? {})) {
      if (!(name in DEFAULT_SETTINGS)) throw nodeError(TypeError, "ERR_HTTP2_INVALID_SETTING_VALUE", `Invalid value for setting "${name}": ${value}`);
    }
    this.pendingSettingsAck = true;
    const started = Date.now();
    this._whenConnected(() => internals.settings(this._id, settings).then(surfaced(() => {
      this.pendingSettingsAck = false;
      Object.assign(this.localSettings, settings);
      this.emit("localSettings", { ...this.localSettings });
      callback?.(null, { ...this.localSettings }, Date.now() - started);
    }), surfaced((err) => { this.pendingSettingsAck = false; callback?.(err); })));
  }

  goaway(code = constants.NGHTTP2_NO_ERROR, lastStreamID = 0, opaqueData) {
    this._whenConnected(() => internals.goaway(this._id, code, lastStreamID, opaqueData === undefined ? undefined : Buffer.from(opaqueData)));
  }

  /** Stops new streams, and closes once the open ones are done, as Node's close(). */
  close(callback) {
    if (callback) this.once("close", callback);
    if (this.closed || this.destroyed) return;
    this.closed = true;
    this._maybeClose();
  }

  _maybeClose() {
    if (!this.closed || this.destroyed || this._streams.size > 0) return;
    if (this._id !== null) internals.close(this._id).then(() => this.destroy(), () => this.destroy());
    else this.destroy();
  }

  destroy(err, code) {
    if (this.destroyed) return;
    this.destroyed = true;
    this.closed = true;
    this.connecting = false;
    this._clearTimer();
    for (const stream of [...this._streams]) stream.destroy(err ? nodeError(Error, "ERR_HTTP2_STREAM_CANCEL", "The pending stream has been canceled (caused by: " + err.message + ")", { cause: err }) : undefined);
    if (this._id !== null) internals.destroy(this._id);
    this._hostEvents?.cancel();
    nextTick(() => {
      if (err) this.emit("error", err);
      this.emit("close");
    });
  }

  setTimeout(ms, callback) {
    this.timeout = Number(ms) || 0;
    if (callback) this.once("timeout", callback);
    this._touch();
    return this;
  }

  _touch() {
    this._clearTimer();
    if (this.timeout > 0 && !this.destroyed) this._timer = setTimeout(() => { this._timer = null; this.emit("timeout"); }, this.timeout);
  }

  _clearTimer() { if (this._timer) { clearTimeout(this._timer); this._timer = null; } }
  setLocalWindowSize() {}
  ref() {}
  unref() {}
}

/** A connection failure as Node's error: the socket's code, message and fields. */
function sessionError(err, host) {
  if (!err || typeof err !== "object") return nodeError(Error, "ERR_HTTP2_ERROR", String(err));
  if (err instanceof Error && err.code && err.syscall) return err;
  let code = err.code;
  let message = String(err.message ?? err);
  const extra = {};
  if (code === "dns_failed") {
    code = "ENOTFOUND";
    message = `getaddrinfo ENOTFOUND ${host}`;
    Object.assign(extra, { errno: -3008, syscall: "getaddrinfo", hostname: host });
  } else if (code === "ECONNREFUSED" || code === "ETIMEDOUT") {
    const at = /\s(\[?[0-9a-fA-F.:]+\]?):(\d+)$/.exec(message);
    Object.assign(extra, { errno: code === "ECONNREFUSED" ? -111 : -110, syscall: "connect", address: at ? at[1].replace(/^\[|\]$/g, "") : host,
      port: at ? Number(at[2]) : undefined });
  }
  return nodeError(Error, code ?? "ERR_HTTP2_ERROR", message, extra);
}

let warnedInsecure = false;

/** http2.connect(authority[, options][, listener]). */
export function connect(authority, options, listener) {
  if (typeof options === "function") { listener = options; options = {}; }
  options = options ?? {};
  let url;
  try {
    url = authority instanceof URL ? authority : new URL(typeof authority === "string" ? authority : `${authority.protocol ?? "https:"}//${authority.host ?? authority.hostname}${authority.port ? ":" + authority.port : ""}`);
  } catch {
    throw nodeError(TypeError, "ERR_INVALID_URL", "Invalid URL");
  }
  if (url.protocol !== "https:" && url.protocol !== "http:") {
    throw nodeError(TypeError, "ERR_HTTP2_UNSUPPORTED_PROTOCOL", `protocol "${url.protocol}" is unsupported.`);
  }
  if (typeof options.createConnection === "function") {
    // http2-wrapper (got's) opens the TLS socket itself, with ALPN h2, and hands it over. One of the isolate's own
    // sockets already went through the egress rules; the host runs HTTP/2 on its own connection, so that one is closed
    // and the session opens to the same origin, with the same options, through the rules again. Anything else would
    // bypass them.
    const given = options.createConnection(url, options);
    if (!(given instanceof Socket)) throw unsupported("createConnection", "it would bypass the egress rules");
    given.destroy();
  }
  if (options.pfx !== undefined) throw unsupported("A PFX bundle", "pass PEM cert and key");
  const session = new ClientHttp2Session(url, options);
  if (listener) session.once("connect", listener);
  const tlsOptions = {};
  if (session.encrypted) {
    if (options.rejectUnauthorized === false || (options.rejectUnauthorized === undefined && globalThis.process?.env?.NODE_TLS_REJECT_UNAUTHORIZED === "0")) {
      tlsOptions.rejectUnauthorized = false;
      if (!warnedInsecure) {
        warnedInsecure = true;
        console.warn("http2: certificate verification is off for a session (rejectUnauthorized: false); only the job itself is affected");
      }
    }
    const pem = (value) => (value === undefined ? undefined : Array.isArray(value) ? value.map(pem) : typeof value === "string" ? value : Buffer.from(value).toString("utf8"));
    const context = options.secureContext?.context ?? {};
    if (options.ca !== undefined || context.ca !== undefined) tlsOptions.ca = pem(options.ca) ?? context.ca;
    if (options.cert !== undefined || context.cert !== undefined) tlsOptions.cert = pem(options.cert) ?? context.cert;
    if (options.key !== undefined || context.key !== undefined) tlsOptions.key = pem(options.key) ?? context.key;
    if (session._servername) tlsOptions.servername = session._servername;
    if (options.minVersion) tlsOptions.minVersion = options.minVersion;
    if (options.maxVersion) tlsOptions.maxVersion = options.maxVersion;
  }
  session._connect(tlsOptions);
  return session;
}

export function createServer() { throw unsupported("http2.createServer", NO_SERVERS); }
export function createSecureServer() { throw unsupported("http2.createSecureServer", NO_SERVERS); }
export function performServerHandshake() { throw unsupported("http2.performServerHandshake", NO_SERVERS); }
export class Http2ServerRequest extends EventEmitter {}
export class Http2ServerResponse extends EventEmitter {}

export default { constants, sensitiveHeaders, connect, createServer, createSecureServer, performServerHandshake, getDefaultSettings,
  getPackedSettings, getUnpackedSettings, Http2ServerRequest, Http2ServerResponse, ClientHttp2Session, ClientHttp2Stream };
